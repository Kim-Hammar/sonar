package se.kth.sonar.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.belief.BeliefModel;
import se.kth.sonar.belief.BeliefState;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.report.RepairResult;
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

class KoopmanPolicyTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final Dialect DIALECT = Dialect.load("cisco-ios");
  private static final Specification TARGET =
      new Specification(
          new ForwardingPredicate.Reachability(R1, Prefix.parse("10.0.0.0/24")),
          Specification.Status.parse("broken_removed"));
  private static final Snapshot SNAPSHOT =
      Snapshot.of(
          Map.of(
              "r1.cfg",
              "hostname r1\ninterface Loopback0\n ip address 10.0.0.1 255.255.255.255\n"));
  private static final Budget NO_DECISIONS =
      Budget.builder().limit(Budget.Resource.DECISIONS, 0).build();

  private static RepairState state() {
    Topology topology = Topology.of(SNAPSHOT.routers(), List.of());
    RepairProblem problem =
        new RepairProblem("tiny", SNAPSHOT, topology, SpecificationSet.of(List.of(TARGET)));
    Verification verification =
        new Verification(
            SNAPSHOT.id(),
            new Verdict(List.of(TARGET), List.of(), List.of()),
            DataPlaneEvidence.none());
    List<Statement> statements = DIALECT.grammar().parse(R1, SNAPSHOT.text(R1));
    ProgramModel program =
        new ProgramModel(
            SNAPSHOT,
            topology,
            DIALECT.schema(),
            Map.of(R1, statements),
            List.of(),
            List.of(),
            List.of());
    return RepairState.initial(problem, verification, program);
  }

  private static List<Hypothesis> candidates(RepairState state, int count) {
    Statement statement = state.program().statements(R1).get(2);
    List<Hypothesis> hypotheses = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      hypotheses.add(
          Hypothesis.of(
              state.snapshot(),
              List.of(
                  new EditOperator.ReplaceSlots(
                      statement,
                      "address",
                      List.of("10.0.0." + (index + 2)),
                      new Provenance.Existing()))));
    }
    return hypotheses;
  }

  private static Region region(String id, List<Hypothesis> hypotheses) {
    return new SiteRegion(
        new Region.Id(id),
        new ProgramObject.Ref("interface", R1, List.of(id)),
        List.of(TARGET),
        Map.of(),
        new Region.Hypotheses.Admitted(hypotheses));
  }

  /** Returns a space of regions with the given beliefs, each with {@code size} candidates. */
  private static SearchPolicy.Action next(
      KoopmanPolicy policy, Map<String, Double> beliefs, int size) {
    RepairState state = state();
    List<Region> regions = new ArrayList<>();
    Map<Region.Id, Double> weights = new LinkedHashMap<>();
    beliefs.forEach(
        (id, weight) -> {
          regions.add(region(id, candidates(state, size)));
          weights.put(new Region.Id(id), weight);
        });
    SearchSpace space = SearchSpace.partitioned(regions, new SearchSpace.Capacity(16, 64));
    BeliefState belief = new BeliefModel().initial(Distribution.of(weights), 0);
    return policy.next(state, space, belief, NO_DECISIONS);
  }

  private static int verified(SearchPolicy.Action action) {
    return ((SearchPolicy.Action.Verify) action).hypotheses().size();
  }

  private static final KoopmanPolicy POLICY =
      new KoopmanPolicy(new KoopmanPolicy.Parameters(0.5, 1_000), 1);

  /**
   * With θ = 0.5, ln(0.8 / 0.1) / θ = 4.16, so the index of the best region falls to that of the
   * second after 5 candidates.
   */
  @Test
  void verifiesUntilTheIndexFallsToTheSecondBestRegion() {
    Map<String, Double> beliefs = new LinkedHashMap<>();
    beliefs.put("a", 0.8);
    beliefs.put("b", 0.1);
    beliefs.put("c", 0.1);
    assertThat(verified(next(POLICY, beliefs, 10))).isEqualTo(5);
    beliefs.put("b", 0.75);
    beliefs.put("c", 0.05);
    assertThat(verified(next(POLICY, beliefs, 10))).isEqualTo(1);
  }

  @Test
  void neverVerifiesFewerThanTheMinimumBatch() {
    KoopmanPolicy atLeastFive = new KoopmanPolicy(new KoopmanPolicy.Parameters(0.5, 1_000), 5);
    Map<String, Double> beliefs = new LinkedHashMap<>();
    beliefs.put("a", 0.5);
    beliefs.put("b", 0.5);
    assertThat(verified(next(atLeastFive, beliefs, 10))).isEqualTo(5);
    beliefs.put("a", 0.999);
    beliefs.put("b", 0.001);
    assertThat(verified(next(atLeastFive, beliefs, 20))).isEqualTo(14);
    assertThatThrownBy(() -> new KoopmanPolicy(KoopmanPolicy.Parameters.defaults(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void verifiesEveryCandidateOfTheOnlyRegionAndNoMoreThanItHas() {
    assertThat(verified(next(POLICY, Map.of("a", 1.0), 7))).isEqualTo(7);
    Map<String, Double> beliefs = new LinkedHashMap<>();
    beliefs.put("a", 0.999);
    beliefs.put("b", 0.001);
    assertThat(verified(next(POLICY, beliefs, 3))).isEqualTo(3);
  }

  /** With λ = 1, θ b = 0.3 is at most 1 / (λ g) = 1 for one violated specification. */
  @Test
  void stopsWhenTheBestIndexIsWorthLessThanTheViolationsLeft() {
    KoopmanPolicy cheap = new KoopmanPolicy(new KoopmanPolicy.Parameters(0.5, 1), 1);
    Map<String, Double> beliefs = new LinkedHashMap<>();
    beliefs.put("a", 0.6);
    beliefs.put("b", 0.4);
    assertThat(next(cheap, beliefs, 3))
        .isEqualTo(new SearchPolicy.Action.Stop(new RepairResult.Stop.Abandoned()));
    assertThat(next(POLICY, beliefs, 3)).isInstanceOf(SearchPolicy.Action.Verify.class);
  }

  @Test
  void ranksBeforeVerifyingWhenTheRegionHasMoreCandidatesThanItWillVerify() {
    RepairState state = state();
    List<Region> regions =
        List.of(region("a", candidates(state, 6)), region("b", candidates(state, 6)));
    SearchSpace space = SearchSpace.partitioned(regions, new SearchSpace.Capacity(16, 64));
    Map<Region.Id, Double> weights = new LinkedHashMap<>();
    weights.put(new Region.Id("a"), 0.9);
    weights.put(new Region.Id("b"), 0.1);
    BeliefState belief = new BeliefModel().initial(Distribution.of(weights), 0);
    belief = new BeliefModel().afterNeutralAssessment(belief, space.partitions().getFirst());
    assertThat(POLICY.next(state, space, belief, Budget.unlimited()))
        .isEqualTo(new SearchPolicy.Action.Rank(new Region.Id("a")));
  }

  @Test
  void rejectsParametersOutsideTheirRange() {
    assertThatThrownBy(() -> new KoopmanPolicy.Parameters(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KoopmanPolicy.Parameters(1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
