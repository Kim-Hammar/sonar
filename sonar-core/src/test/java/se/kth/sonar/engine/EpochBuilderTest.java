package se.kth.sonar.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.generate.ValueDomains;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.prior.FrontierFactor;
import se.kth.sonar.prior.PriorFactor;
import se.kth.sonar.prior.RegionPrior;
import se.kth.sonar.prior.RelationFactor;
import se.kth.sonar.prior.SpectrumFactor;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.Verification;

class EpochBuilderTest {
  private static final Dialect DIALECT = Dialect.load("cisco-ios");
  private static final RouterId TWO = RouterId.of("2_as65001_r2");

  private static ExecutionContext context;
  private static EpochBuilder epochs;

  @BeforeAll
  static void open() {
    context = ExecutionContext.open(ParallelismConfig.sequential(), List.of());
    ProgramService programs = new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
    Bounds bounds = Bounds.defaults();
    SeedGenerator seeds =
        new SeedGenerator(DIALECT.schema(), new ValueDomains(DIALECT.schema(), bounds), bounds);
    SearchSpaceBuilder spaces =
        new SearchSpaceBuilder(
            seeds,
            new ClosureEngine(programs, seeds, bounds),
            new Admissibility(programs, context),
            new SearchSpace.Capacity(16, 64),
            bounds,
            context);
    epochs =
        new EpochBuilder(
            programs,
            spaces,
            new RegionPrior(
                List.of(
                    new SpectrumFactor(),
                    new RelationFactor(programs.evaluator()),
                    new FrontierFactor())));
  }

  @AfterAll
  static void close() {
    context.close();
  }

  private static Verification verify(RepairProblem problem, Snapshot snapshot) {
    DataPlaneEngine.Observation observation =
        new RepairEngineTest.ScriptedDataPlane().analyze(snapshot, problem.specifications());
    return new Verification(
        snapshot.id(),
        problem.specifications().judge(observation.holding()),
        observation.evidence());
  }

  private static Snapshot edited(Snapshot snapshot, int line, String text) {
    return snapshot
        .patch(
            Transaction.of(
                snapshot,
                List.of(new LineEdit.Replace(new SourceSpan(TWO, line, line), List.of(text)))))
        .after();
  }

  @Test
  void theFirstEpochHoldsTheFaultyNetworkAndAPriorOverItsRegions() {
    RepairProblem problem = RepairEngineTest.problem();
    Epoch epoch = epochs.initial(problem, verify(problem, problem.initial()));
    assertThat(epoch.state().epoch()).isZero();
    assertThat(epoch.state().snapshot()).isEqualTo(problem.initial());
    assertThat(epoch.space().isEmpty()).isFalse();
    Distribution<Region.Id> prior = epochs.prior(epoch);
    assertThat(prior.outcomes())
        .containsExactlyElementsOf(epoch.space().regions().stream().map(Region::id).toList());
    assertThat(prior.asMap().values().stream().mapToDouble(Double::doubleValue).sum())
        .isCloseTo(1, within(1e-9));
    for (Region region : epoch.space().regions()) {
      SequencedMap<String, Double> breakdown = epochs.prior().breakdown(region, epoch.state());
      assertThat(breakdown.keySet())
          .containsExactlyElementsOf(
              epochs.prior().factors().stream().map(PriorFactor::name).toList());
      double product =
          breakdown.values().stream().mapToDouble(Double::doubleValue).reduce(1, (a, b) -> a * b);
      assertThat(epochs.prior().weight(region, epoch.state())).isCloseTo(product, within(1e-12));
    }
  }

  @Test
  void expandingGeneratesCandidatesOrDropsTheRegion() {
    RepairProblem problem = RepairEngineTest.problem();
    Epoch epoch = epochs.initial(problem, verify(problem, problem.initial()));
    int admitted = 0;
    for (Region region : epoch.space().regions()) {
      epoch = epochs.expand(epoch, region.id());
      if (epoch.space().region(region.id()).isPresent()) {
        Region expanded = epoch.space().region(region.id()).orElseThrow();
        assertThat(expanded.hypotheses()).isInstanceOf(Region.Hypotheses.Admitted.class);
        assertThat(expanded.bound()).isPositive();
        admitted++;
      }
    }
    assertThat(admitted).isPositive();
    assertThat(epochs.prior(epoch).outcomes()).hasSize(epoch.space().size());
  }

  @Test
  void advancingRefreshesTheSpaceUntilTheNetworkIsRepaired() {
    RepairProblem problem = RepairEngineTest.problem();
    Epoch epoch = epochs.initial(problem, verify(problem, problem.initial()));
    Snapshot session = edited(problem.initial(), 11, " neighbor 10.0.0.1 remote-as 65001");
    Epoch partly = epochs.advance(epoch, session, verify(problem, session));
    assertThat(partly.state().epoch()).isEqualTo(1);
    assertThat(partly.state().solved()).isFalse();
    assertThat(partly.space().isEmpty()).isFalse();
    Snapshot repaired = edited(session, 8, " network 10.1.0.0 0.0.0.1 area 0");
    Epoch done = epochs.advance(partly, repaired, verify(problem, repaired));
    assertThat(done.state().epoch()).isEqualTo(2);
    assertThat(done.state().solved()).isTrue();
    assertThat(done.space().isEmpty()).isTrue();
    assertThat(done.space().capacity()).isEqualTo(epoch.space().capacity());
  }

  @Test
  void withMultipliesInOneMoreFactor() {
    RepairProblem problem = RepairEngineTest.problem();
    Epoch epoch = epochs.initial(problem, verify(problem, problem.initial()));
    PriorFactor doubling =
        new PriorFactor() {
          @Override
          public String name() {
            return "doubling";
          }

          @Override
          public double weight(Region region, RepairState state) {
            return 2;
          }
        };
    EpochBuilder extended = epochs.with(doubling);
    Region region = epoch.space().regions().getFirst();
    assertThat(extended.prior().breakdown(region, epoch.state()))
        .containsEntry("doubling", 2.0)
        .hasSize(epochs.prior().factors().size() + 1);
    assertThat(extended.prior().weight(region, epoch.state()))
        .isCloseTo(2 * epochs.prior().weight(region, epoch.state()), within(1e-12));
    assertThat(extended.prior(epoch).asMap()).isEqualTo(epochs.prior(epoch).asMap());
  }
}
