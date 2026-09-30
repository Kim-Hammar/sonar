package se.kth.sonar.prior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleBiFunction;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

class RegionPriorTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final Specification TARGET =
      new Specification(
          new ForwardingPredicate.Reachability(R1, Prefix.parse("10.0.0.0/24")),
          Specification.Status.parse("broken_removed"));

  private static RepairState state() {
    Snapshot snapshot = Snapshot.of(Map.of("r1.cfg", "hostname r1\n"));
    RepairProblem problem =
        new RepairProblem(
            "tiny",
            snapshot,
            Topology.of(snapshot.routers(), List.of()),
            SpecificationSet.of(List.of(TARGET)));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(TARGET), List.of(), List.of()),
            DataPlaneEvidence.none());
    ProgramModel program =
        new ProgramModel(
            snapshot,
            problem.topology(),
            Dialect.load("cisco-ios").schema(),
            Map.of(),
            List.of(),
            List.of(),
            List.of());
    return RepairState.initial(problem, verification, program);
  }

  private static SearchSpace space(String... ids) {
    List<Region> regions =
        java.util.Arrays.stream(ids)
            .<Region>map(
                id ->
                    new SiteRegion(
                        new Region.Id(id),
                        new ProgramObject.Ref("interface", R1, List.of(id)),
                        List.of(TARGET),
                        Map.of(),
                        new Region.Hypotheses.Deferred(1)))
            .toList();
    return SearchSpace.partitioned(regions, new SearchSpace.Capacity(10, 100));
  }

  private static PriorFactor factor(String name, ToDoubleBiFunction<Region, RepairState> weight) {
    return new PriorFactor() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public double weight(Region region, RepairState state) {
        return weight.applyAsDouble(region, state);
      }
    };
  }

  @Test
  void multipliesFactorsAndNormalises() {
    PriorFactor favoursA =
        factor("favours-a", (region, state) -> region.id().value().equals("a") ? 2 : 1);
    PriorFactor constant = factor("constant", (region, state) -> 3);
    Distribution<Region.Id> prior =
        new RegionPrior(List.of(favoursA, constant)).over(space("a", "b"), state());
    assertThat(prior.probability(new Region.Id("a"))).isCloseTo(2.0 / 3, within(1e-12));
    assertThat(prior.probability(new Region.Id("b"))).isCloseTo(1.0 / 3, within(1e-12));
  }

  @Test
  void breaksTheWeightDownByFactor() {
    RegionPrior prior =
        new RegionPrior(
            List.of(
                factor("favours-a", (region, state) -> region.id().value().equals("a") ? 2 : 1),
                factor("constant", (region, state) -> 3)));
    Region a = space("a", "b").region(new Region.Id("a")).orElseThrow();
    assertThat(prior.breakdown(a, state()))
        .containsExactly(Map.entry("favours-a", 2.0), Map.entry("constant", 3.0));
    assertThat(prior.weight(a, state())).isEqualTo(6);
    assertThat(prior.factors())
        .extracting(PriorFactor::name)
        .containsExactly("favours-a", "constant");
  }

  @Test
  void withoutFactorsThePriorIsUniform() {
    Distribution<Region.Id> prior =
        new RegionPrior(List.of()).over(space("a", "b", "c", "d"), state());
    assertThat(prior.probability(new Region.Id("c"))).isEqualTo(0.25);
  }

  @Test
  void rejectsInvalidFactorWeights() {
    RepairState state = state();
    SearchSpace space = space("a");
    assertThatThrownBy(
            () -> new RegionPrior(List.of(factor("negative", (r, s) -> -1))).over(space, state))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("negative");
    assertThatThrownBy(
            () ->
                new RegionPrior(List.of(factor("undefined", (r, s) -> Double.NaN)))
                    .over(space, state))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void talliesUseTheJeffreysPrior() {
    assertThat(Tally.empty().probability()).isEqualTo(0.5);
    assertThat(Tally.empty().odds()).isEqualTo(1.0);
    Tally tally = new Tally(3, 1);
    assertThat(tally.probability()).isCloseTo(0.7, within(1e-12));
    assertThat(tally.odds()).isCloseTo(3.5 / 1.5, within(1e-12));
    assertThat(tally.plus(true)).isEqualTo(new Tally(4, 1));
    assertThat(tally.plus(false)).isEqualTo(new Tally(3, 2));
    assertThatThrownBy(() -> new Tally(-1, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
