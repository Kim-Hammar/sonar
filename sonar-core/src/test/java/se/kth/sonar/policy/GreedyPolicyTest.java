package se.kth.sonar.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import se.kth.sonar.belief.BeliefModel;
import se.kth.sonar.belief.BeliefState;
import se.kth.sonar.common.ContentHash;
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
import se.kth.sonar.relation.AttributePath;
import se.kth.sonar.relation.Constraint;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.Partition;
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

class GreedyPolicyTest {
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

  private final GreedyPolicy policy = new GreedyPolicy(2);

  private static RepairState state(boolean solved) {
    Topology topology = Topology.of(SNAPSHOT.routers(), List.of());
    RepairProblem problem =
        new RepairProblem("tiny", SNAPSHOT, topology, SpecificationSet.of(List.of(TARGET)));
    Verdict verdict =
        solved
            ? new Verdict(List.of(), List.of(TARGET), List.of())
            : new Verdict(List.of(TARGET), List.of(), List.of());
    Verification verification = new Verification(SNAPSHOT.id(), verdict, DataPlaneEvidence.none());
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

  private static Region region(String id, Region.Hypotheses hypotheses) {
    return new SiteRegion(
        new Region.Id(id),
        new ProgramObject.Ref("interface", R1, List.of(id)),
        List.of(TARGET),
        Map.of(),
        hypotheses);
  }

  private static Hypothesis hypothesis(RepairState state, String address) {
    Statement statement = state.program().statements(R1).get(2);
    return Hypothesis.of(
        state.snapshot(),
        List.of(
            new EditOperator.ReplaceSlots(
                statement, "address", List.of(address), new Provenance.Existing())));
  }

  private static BeliefState belief(SearchSpace space) {
    Distribution<Region.Id> prior =
        Distribution.uniform(space.regions().stream().map(Region::id).toList());
    return new BeliefModel().initial(prior, 0);
  }

  @Test
  void stopsWhenSolvedOutOfBudgetOrOutOfRegions() {
    SearchSpace space =
        SearchSpace.partitioned(
            List.of(region("a", new Region.Hypotheses.Deferred(3))),
            new SearchSpace.Capacity(4, 20));
    assertThat(policy.next(state(true), space, belief(space), Budget.unlimited()))
        .isEqualTo(new SearchPolicy.Action.Stop(new RepairResult.Stop.Solved()));
    Budget spent = Budget.builder().limit(Budget.Resource.VERIFICATIONS, 1).build();
    spent.charge(Budget.Resource.VERIFICATIONS, 1);
    assertThat(policy.next(state(false), space, belief(space), spent))
        .isEqualTo(
            new SearchPolicy.Action.Stop(
                new RepairResult.Stop.BudgetExhausted(Budget.Resource.VERIFICATIONS)));
    SearchSpace empty = space.without(List.of(new Region.Id("a")));
    assertThat(policy.next(state(false), empty, belief(space), Budget.unlimited()))
        .isEqualTo(new SearchPolicy.Action.Stop(new RepairResult.Stop.SearchExhausted()));
  }

  @Test
  void assessesThenExpandsThenRanksThenVerifies() {
    RepairState state = state(false);
    List<Hypothesis> hypotheses = new ArrayList<>();
    for (String address : List.of("10.0.0.2", "10.0.0.3", "10.0.0.4")) {
      hypotheses.add(hypothesis(state, address));
    }
    SearchSpace space =
        SearchSpace.partitioned(
            List.of(
                region("a", new Region.Hypotheses.Deferred(3)),
                region("b", new Region.Hypotheses.Deferred(3))),
            new SearchSpace.Capacity(4, 20));
    BeliefState belief = belief(space);
    SearchPolicy.Action first = policy.next(state, space, belief, Budget.unlimited());
    Partition comparison = Partition.comparison(List.of(new Region.Id("a"), new Region.Id("b")));
    assertThat(first).isEqualTo(new SearchPolicy.Action.Assess(comparison));
    belief = new BeliefModel().afterNeutralAssessment(belief, comparison);
    assertThat(policy.next(state, space, belief, Budget.unlimited()))
        .isEqualTo(new SearchPolicy.Action.Expand(new Region.Id("a")));
    space = space.replace(region("a", new Region.Hypotheses.Admitted(hypotheses)));
    assertThat(policy.next(state, space, belief, Budget.unlimited()))
        .isEqualTo(new SearchPolicy.Action.Rank(new Region.Id("a")));
    Distribution<ContentHash> ranking =
        Distribution.of(
            Map.of(
                hypotheses.get(0).id(),
                0.1,
                hypotheses.get(1).id(),
                0.6,
                hypotheses.get(2).id(),
                0.3));
    Region.Hypotheses.Admitted ranked =
        new Region.Hypotheses.Admitted(hypotheses).rankedBy(ranking);
    space = space.replace(region("a", ranked));
    assertThat(policy.next(state, space, belief, Budget.unlimited()))
        .isEqualTo(
            new SearchPolicy.Action.Verify(
                new Region.Id("a"), List.of(hypotheses.get(1).id(), hypotheses.get(2).id())));
    Budget noDecisions = Budget.builder().limit(Budget.Resource.DECISIONS, 0).build();
    space = space.replace(region("a", new Region.Hypotheses.Admitted(hypotheses)));
    assertThat(policy.next(state, space, belief(space), noDecisions))
        .isEqualTo(
            new SearchPolicy.Action.Verify(
                new Region.Id("a"), List.of(hypotheses.get(0).id(), hypotheses.get(1).id())));
    assertThat(ranked.without(List.of(hypotheses.get(1).id())).ranking().orElseThrow().argmax())
        .isEqualTo(hypotheses.get(2).id());
    assertThat(ranked.ranking().orElseThrow().probability(hypotheses.get(1).id()))
        .isCloseTo(0.6, within(1e-12));
  }

  @Test
  void aCandidateWithAnInventedValueRanksAfterTheOthersTheRankingGivesAChance() {
    RepairState state = state(false);
    Hypothesis observed = hypothesis(state, "10.0.0.2");
    Hypothesis invented =
        Hypothesis.of(
            state.snapshot(),
            List.of(
                new EditOperator.ReplaceSlots(
                    state.program().statements(R1).get(2),
                    "address",
                    List.of("10.0.0.3"),
                    new Provenance.Forced(
                        "address-identity",
                        new Requirement(
                            Requirement.Strength.SOFT,
                            new Constraint.Exists(new AttributePath("owner", List.of())),
                            Optional.empty()),
                        ValueSupport.INVENTED))));
    Hypothesis hopeless = hypothesis(state, "10.0.0.4");
    Region.Hypotheses.Admitted ranked =
        new Region.Hypotheses.Admitted(List.of(hopeless, invented, observed))
            .rankedBy(
                Distribution.of(
                    Map.of(invented.id(), 0.9, observed.id(), 0.1, hopeless.id(), 0.0)));
    assertThat(ranked.list()).containsExactly(observed, invented, hopeless);
    assertThat(ranked.ranking().orElseThrow().probability(invented.id()))
        .isCloseTo(0.9, within(1e-12));

    Region.Hypotheses.Admitted tried = ranked.without(List.of(observed.id()));
    assertThat(tried.list()).containsExactly(invented, hopeless);
    assertThat(tried.without(List.of(invented.id())).restored())
        .as("a region carried into another state starts from all its candidates, as ranked")
        .isEqualTo(ranked);
    assertThat(ranked.restored()).isEqualTo(ranked);
  }
}
