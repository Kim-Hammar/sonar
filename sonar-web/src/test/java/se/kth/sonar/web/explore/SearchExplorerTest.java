package se.kth.sonar.web.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.Pipeline;
import se.kth.sonar.app.TestDataset;
import se.kth.sonar.belief.BeliefModel;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.decision.DecisionModel;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.DecisionProblemFactory;
import se.kth.sonar.decision.DecisionService;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.engine.Adoption;
import se.kth.sonar.engine.Epoch;
import se.kth.sonar.engine.EpochBuilder;
import se.kth.sonar.engine.RepairEngine;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.policy.SearchPolicy;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceRecord;
import se.kth.sonar.trace.TraceSink;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.VerificationService;
import se.kth.sonar.web.ScriptedDataPlane;
import se.kth.sonar.web.verify.ScenarioVerifier;

/** The explorer builds what the engine builds: same regions, partitions, prior and candidates. */
class SearchExplorerTest {
  private static final Dialect DIALECT = Dialect.load("cisco-ios");
  private static final SearchExplorer.Key KEY = new SearchExplorer.Key("scenario-001", 10);

  @TempDir Path dataset;

  /** A decision model no test may reach. */
  private static final class NeverAsked implements DecisionModel {
    @Override
    public DecisionOutcome<RegionAssessment> assess(DecisionProblem.RegionChoice problem) {
      throw new AssertionError("the model was asked");
    }

    @Override
    public DecisionOutcome<HypothesisRanking> rank(DecisionProblem.HypothesisChoice problem) {
      throw new AssertionError("the model was asked");
    }
  }

  private static SearchExplorer.Exploration await(
      SearchExplorer explorer, Predicate<SearchExplorer.Exploration> settled) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      Optional<SearchExplorer.Exploration> exploration = explorer.exploration(KEY);
      if (exploration.isPresent() && settled.test(exploration.get())) {
        return exploration.get();
      }
      Thread.sleep(20);
    }
    throw new AssertionError("the exploration never settled");
  }

  private static boolean expansionSettled(SearchExplorer.Exploration exploration, Region.Id id) {
    return exploration
        .model()
        .map(model -> model.expansions().get(id))
        .map(expansion -> !expansion.active())
        .orElse(false);
  }

  private static TraceEvent.SpaceBuilt engineSpace(
      ScenarioRepository repository, ExecutionContext context) throws Exception {
    List<TraceRecord> records = new CopyOnWriteArrayList<>();
    try (ResourcePool<DataPlaneEngine> engines =
            ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close);
        ResourcePool<DecisionModel> models = ResourcePool.of(1, NeverAsked::new, model -> {})) {
      VerificationService verifier = new VerificationService(engines, context);
      EpochBuilder epochs = Pipeline.epochs(DIALECT, context);
      RepairEngine engine =
          new RepairEngine(
              new RepairEngine.Components(
                  epochs,
                  verifier,
                  new DecisionProblemFactory(60_000),
                  new DecisionService(models, context),
                  new BeliefModel(),
                  (state, space, belief, budget) ->
                      new SearchPolicy.Action.Stop(new RepairResult.Stop.SearchExhausted()),
                  new Adoption(verifier, epochs.programs())));
      RepairProblem problem = repository.load(KEY.scenario(), KEY.percent());
      ScopedValue.where(TraceSink.CURRENT, (TraceSink) records::add)
          .call(() -> engine.repair(problem));
    }
    return records.stream()
        .map(TraceRecord::event)
        .filter(TraceEvent.SpaceBuilt.class::isInstance)
        .map(TraceEvent.SpaceBuilt.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void buildsTheEpochTheEngineStartsFromAndExpandsItTheSameWay() throws Exception {
    TestDataset.create(dataset, 1);
    ScenarioRepository repository = new ScenarioRepository(dataset);
    Clock clock = Clock.systemUTC();
    try (ExecutionContext context =
            ExecutionContext.open(ParallelismConfig.sequential(), List.of());
        ScenarioVerifier verifier =
            new ScenarioVerifier(repository, ScriptedDataPlane::new, clock, 8);
        SearchExplorer explorer =
            new SearchExplorer(
                repository, verifier, DIALECT, ParallelismConfig.sequential(), clock, 4)) {
      assertThat(explorer.factors())
          .containsExactly(
              "spectrum",
              "relation",
              "agreement-outlier",
              "first-match-dominance",
              "counterexample",
              "policy-cut",
              "path-differential",
              "frontier",
              "drop");
      assertThat(explorer.request(KEY).queued()).isTrue();
      SearchExplorer.Exploration built =
          await(explorer, e -> e.status() == SearchExplorer.Status.FINISHED);
      SearchExplorer.Model model = built.model().orElseThrow();
      assertThat(model.epoch().space().size()).isGreaterThan(3);
      TraceEvent.SpaceBuilt reference = engineSpace(repository, context);
      assertThat(model.epoch().space().regions()).isEqualTo(reference.space().regions());
      assertThat(model.epoch().space().partitions()).isEqualTo(reference.space().partitions());
      assertThat(model.prior().asMap()).isEqualTo(reference.prior().orElseThrow().asMap());
      for (Region region : model.epoch().space().regions()) {
        double product =
            model.breakdown().get(region.id()).values().stream()
                .mapToDouble(Double::doubleValue)
                .reduce(1, (a, b) -> a * b);
        assertThat(model.prior().probability(region.id()))
            .isCloseTo(
                product
                    / model.epoch().space().regions().stream()
                        .mapToDouble(
                            r ->
                                model.breakdown().get(r.id()).values().stream()
                                    .mapToDouble(Double::doubleValue)
                                    .reduce(1, (a, b) -> a * b))
                        .sum(),
                within(1e-9));
      }

      EpochBuilder epochs = Pipeline.epochs(DIALECT, context);
      Epoch expected =
          epochs.initial(
              repository.load(KEY.scenario(), KEY.percent()), model.epoch().state().verification());
      List<Region.Id> first =
          model.epoch().space().regions().stream().map(Region::id).limit(3).toList();
      List<Region.Id> admitted = new ArrayList<>();
      for (Region.Id id : first) {
        assertThat(explorer.expand(KEY, id).queued()).isTrue();
        SearchExplorer.Exploration expanded = await(explorer, e -> expansionSettled(e, id));
        expected = epochs.expand(expected, id);
        SearchExplorer.Model current = expanded.model().orElseThrow();
        assertThat(current.epoch().space().region(id).map(Region::hypotheses))
            .isEqualTo(expected.space().region(id).map(Region::hypotheses));
        if (current.epoch().space().region(id).isPresent()) {
          admitted.add(id);
          assertThat(current.expansions().get(id).state())
              .isEqualTo(SearchExplorer.Expansion.State.ADMITTED);
        } else {
          assertThat(current.dropped()).containsKey(id);
          assertThat(current.expansions().get(id).state())
              .isEqualTo(SearchExplorer.Expansion.State.DROPPED);
        }
        assertThat(current.prior().asMap()).isEqualTo(epochs.prior(expected).asMap());
      }
      assertThat(explorer.expand(KEY, first.getFirst()).queued()).isFalse();
      if (!admitted.isEmpty()) {
        Region region =
            explorer
                .exploration(KEY)
                .orElseThrow()
                .model()
                .orElseThrow()
                .epoch()
                .space()
                .region(admitted.getFirst())
                .orElseThrow();
        Region.Hypotheses.Admitted hypotheses = (Region.Hypotheses.Admitted) region.hypotheses();
        assertThat(hypotheses.list()).extracting(Hypothesis::id).doesNotHaveDuplicates();
      }
    }
  }
}
