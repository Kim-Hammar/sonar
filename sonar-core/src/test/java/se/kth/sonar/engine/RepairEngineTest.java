package se.kth.sonar.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.kth.sonar.belief.BeliefModel;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.decision.DecisionModel;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.DecisionProblemFactory;
import se.kth.sonar.decision.DecisionService;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.generate.ValueDomains;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.policy.GreedyPolicy;
import se.kth.sonar.prior.FrontierFactor;
import se.kth.sonar.prior.RegionPrior;
import se.kth.sonar.prior.RelationFactor;
import se.kth.sonar.prior.SpectrumFactor;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceRecord;
import se.kth.sonar.trace.TraceSink;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Runs the whole engine on a two-router network with two independent faults, judged by a scripted
 * data plane: the BGP session comes up when r2 names r1's AS, and OSPF adjacency forms when r2 puts
 * the link interface in area 0, by its network statement or on the interface.
 */
class RepairEngineTest {
  private static final String R1 =
      """
      hostname r1
      interface Loopback0
       ip address 10.0.0.1 255.255.255.255
      interface GigabitEthernet0/0
       ip address 10.1.0.1 255.255.255.254
       ip ospf 1 area 0
      router ospf 1
       router-id 10.0.0.1
       network 10.1.0.0 0.0.0.1 area 0
      router bgp 65001
       bgp router-id 10.0.0.1
       neighbor IBGP peer-group
       neighbor IBGP remote-as 65001
       neighbor 10.0.0.2 peer-group IBGP
       address-family ipv4
        neighbor 10.0.0.2 activate
       exit-address-family
      """;
  private static final String R2 =
      """
      hostname r2
      interface Loopback0
       ip address 10.0.0.2 255.255.255.255
      interface GigabitEthernet0/0
       ip address 10.1.0.0 255.255.255.254
      router ospf 1
       router-id 10.0.0.2
       network 10.1.0.0 0.0.0.1 area 1
      router bgp 65001
       bgp router-id 10.0.0.2
       neighbor 10.0.0.1 remote-as 65009
       address-family ipv4
        neighbor 10.0.0.1 activate
       exit-address-family
      """;
  private static final RouterId ONE = RouterId.of("1_as65001_r1");
  private static final RouterId TWO = RouterId.of("2_as65001_r2");
  private static final Specification SESSION =
      new Specification(
          new ForwardingPredicate.Reachability(ONE, Prefix.parse("10.0.0.2/32")),
          Specification.Status.parse("broken_removed"));
  private static final Specification ADJACENCY =
      new Specification(
          new ForwardingPredicate.Reachability(TWO, Prefix.parse("10.0.0.1/32")),
          Specification.Status.parse("broken_removed"));
  private static final Specification INTACT =
      new Specification(
          new ForwardingPredicate.Reachability(ONE, Prefix.parse("10.1.0.0/31")),
          Specification.Status.parse("intact"));
  private static final Dialect DIALECT = Dialect.load("cisco-ios");

  private static ExecutionContext context;
  private static final AtomicInteger analyses = new AtomicInteger();

  /** Judges the two specifications from r2's text alone. */
  static final class ScriptedDataPlane implements DataPlaneEngine {
    @Override
    public Observation analyze(Snapshot snapshot, SpecificationSet specifications) {
      analyses.incrementAndGet();
      String text = snapshot.text(TWO).text();
      Set<ForwardingPredicate> holding = new HashSet<>();
      holding.add(INTACT.predicate());
      if (text.contains("neighbor 10.0.0.1 remote-as 65001")) {
        holding.add(SESSION.predicate());
      }
      boolean interfaceArea =
          text.contains("ip address 10.1.0.0 255.255.255.254\n ip ospf 1 area 0");
      if (text.contains("network 10.1.0.0 0.0.0.1 area 0") || interfaceArea) {
        holding.add(ADJACENCY.predicate());
      }
      Map<ForwardingPredicate, List<DataPlaneEvidence.Trace>> traces = new LinkedHashMap<>();
      for (Specification specification : specifications.all()) {
        ForwardingPredicate predicate = specification.predicate();
        RouterId other = predicate.source().equals(ONE) ? TWO : ONE;
        traces.put(
            predicate,
            List.of(
                holding.contains(predicate)
                    ? new DataPlaneEvidence.Trace(
                        List.of(predicate.source(), other), DataPlaneEvidence.Disposition.ACCEPTED)
                    : new DataPlaneEvidence.Trace(
                        List.of(predicate.source()), DataPlaneEvidence.Disposition.NO_ROUTE)));
      }
      return new Observation(holding, new DataPlaneEvidence(traces, Map.of(), Map.of()));
    }

    @Override
    public void close() {}
  }

  /** Answers every question with indifference. */
  private static final class IndifferentModel implements DecisionModel {
    @Override
    public DecisionOutcome<RegionAssessment> assess(DecisionProblem.RegionChoice problem) {
      return new DecisionOutcome.Answered<>(
          new RegionAssessment(
              Distribution.uniform(problem.regions().stream().map(r -> r.id()).toList()), 0.2),
          new DecisionOutcome.Usage(1, problem.size() / 4, 20),
          List.of());
    }

    @Override
    public DecisionOutcome<HypothesisRanking> rank(DecisionProblem.HypothesisChoice problem) {
      return new DecisionOutcome.Answered<>(
          HypothesisRanking.by(
              Distribution.uniform(problem.hypotheses().stream().map(Hypothesis::id).toList())),
          new DecisionOutcome.Usage(1, problem.size() / 4, 20),
          List.of());
    }
  }

  @BeforeAll
  static void open() {
    context = ExecutionContext.open(ParallelismConfig.sequential(), List.of());
  }

  @AfterAll
  static void close() {
    context.close();
  }

  static RepairProblem problem() {
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", R1, "2_as65001_r2.cfg", R2));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(ONE, "GigabitEthernet0/0"),
                    new Topology.Endpoint(TWO, "GigabitEthernet0/0"))));
    return new RepairProblem(
        "pair", snapshot, topology, SpecificationSet.of(List.of(SESSION, ADJACENCY, INTACT)));
  }

  private static RepairEngine engine(ResourcePool<DataPlaneEngine> engines) {
    ProgramService programs = new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
    VerificationService verifier = new VerificationService(engines, context);
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
    ResourcePool<DecisionModel> models = ResourcePool.of(1, IndifferentModel::new, model -> {});
    RegionPrior prior =
        new RegionPrior(
            List.of(
                new SpectrumFactor(),
                new RelationFactor(programs.evaluator()),
                new FrontierFactor()));
    return new RepairEngine(
        new RepairEngine.Components(
            new EpochBuilder(programs, spaces, prior),
            verifier,
            new DecisionProblemFactory(60_000),
            new DecisionService(models, context),
            new BeliefModel(),
            new GreedyPolicy(),
            new Adoption(verifier)));
  }

  @Test
  void repairsBothFaultsAndRecordsTheRun() throws Exception {
    analyses.set(0);
    List<TraceRecord> records = new CopyOnWriteArrayList<>();
    Budget budget = Budget.builder().limit(Budget.Resource.VERIFICATIONS, 80).build();
    RunScope scope = new RunScope("pair", budget, 1, new Cancellation());
    RepairProblem problem = problem();
    RepairResult result;
    try (ResourcePool<DataPlaneEngine> engines =
        ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close)) {
      RepairEngine engine = engine(engines);
      result =
          ScopedValue.where(RunScope.CURRENT, scope)
              .where(TraceSink.CURRENT, (TraceSink) records::add)
              .call(() -> engine.repair(problem));
    }
    assertThat(result.stop()).isEqualTo(new RepairResult.Stop.Solved());
    assertThat(result.verdict().solved()).isTrue();
    assertThat(result.repaired().text(TWO).text()).contains("neighbor 10.0.0.1 remote-as 65001");
    assertThat(result.repaired().text(TWO).text())
        .containsAnyOf("network 10.1.0.0 0.0.0.1 area 0", " ip ospf 1 area 0");
    assertThat(result.usage().get(Budget.Resource.VERIFICATIONS)).isEqualTo((long) analyses.get());
    assertThat(analyses.get()).isLessThanOrEqualTo(40);
    List<String> events = new ArrayList<>();
    records.forEach(record -> events.add(record.event().getClass().getSimpleName()));
    assertThat(events).startsWith("Baseline", "SpaceBuilt").contains("Decided", "Tried", "Adopted");
    long adopted = records.stream().filter(r -> r.event() instanceof TraceEvent.Adopted).count();
    assertThat(adopted).isBetween(1L, 2L);
    assertThat(result.adopted()).hasSizeBetween(2, 3);
  }

  @Test
  void adoptionTakesLineDisjointImprovementsTogether() {
    RepairProblem problem = problem();
    try (ResourcePool<DataPlaneEngine> engines =
        ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close)) {
      ProgramService programs =
          new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
      VerificationService verifier = new VerificationService(engines, context);
      Verification baseline = verifier.verify(problem.initial(), problem.specifications());
      RepairState state =
          RepairState.initial(
              problem, baseline, programs.model(problem.initial(), problem.topology()));
      Hypothesis session = replace(state, 11, "asn", "65001");
      Hypothesis area = replace(state, 8, "area", "0");
      Hypothesis idle = replace(state, 11, "asn", "65002");
      List<Adoption.Verified> verified = new ArrayList<>();
      for (Hypothesis hypothesis : List.of(idle, area, session)) {
        Snapshot snapshot = state.snapshot().patch(hypothesis.transaction()).after();
        verified.add(
            new Adoption.Verified(hypothesis, verifier.verify(snapshot, problem.specifications())));
      }
      Adoption.Adopted adopted = new Adoption(verifier).adopt(state, verified).orElseThrow();
      assertThat(adopted.hypotheses()).containsExactlyInAnyOrder(session, area);
      assertThat(adopted.verification().verdict().solved()).isTrue();
      assertThat(new Adoption(verifier).adopt(state, List.of(verified.getFirst()))).isEmpty();
    }
  }

  @Test
  void adoptionPreservesDecisionOrderWhenImprovementsAreOtherwiseEqual() {
    RepairProblem problem = problem();
    try (ResourcePool<DataPlaneEngine> engines =
        ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close)) {
      ProgramService programs =
          new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
      VerificationService verifier = new VerificationService(engines, context);
      Verification baseline = verifier.verify(problem.initial(), problem.specifications());
      RepairState state =
          RepairState.initial(
              problem, baseline, programs.model(problem.initial(), problem.topology()));
      Hypothesis firstValue = replace(state, 11, "asn", "65001");
      Hypothesis secondValue = replace(state, 11, "asn", "65000");
      List<Hypothesis> decisionOrder =
          firstValue.id().compareTo(secondValue.id()) > 0
              ? List.of(firstValue, secondValue)
              : List.of(secondValue, firstValue);
      List<Adoption.Verified> tied = new ArrayList<>();
      for (Hypothesis hypothesis : decisionOrder) {
        Snapshot candidate = state.snapshot().patch(hypothesis.transaction()).after();
        tied.add(
            new Adoption.Verified(
                hypothesis,
                new Verification(
                    candidate.id(),
                    new se.kth.sonar.spec.Verdict(List.of(ADJACENCY), List.of(SESSION), List.of()),
                    DataPlaneEvidence.none())));
      }

      Adoption.Adopted adopted = new Adoption(verifier).adopt(state, tied).orElseThrow();

      assertThat(adopted.hypotheses()).containsExactly(decisionOrder.getFirst());
    }
  }

  private static Hypothesis replace(RepairState state, int line, String slot, String value) {
    return Hypothesis.of(
        state.snapshot(),
        List.of(
            new EditOperator.ReplaceSlots(
                state.program().statements(TWO).stream()
                    .filter(statement -> statement.span().first() == line)
                    .findFirst()
                    .orElseThrow(),
                slot,
                List.of(value),
                new Provenance.Existing())));
  }

  @Test
  void stopsWhenTheVerificationBudgetRunsOut() throws Exception {
    Budget budget = Budget.builder().limit(Budget.Resource.VERIFICATIONS, 2).build();
    RunScope scope = new RunScope("pair", budget, 1, new Cancellation());
    RepairProblem problem = problem();
    try (ResourcePool<DataPlaneEngine> engines =
        ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close)) {
      RepairEngine engine = engine(engines);
      RepairResult result =
          ScopedValue.where(RunScope.CURRENT, scope).call(() -> engine.repair(problem));
      assertThat(result.stop())
          .isEqualTo(new RepairResult.Stop.BudgetExhausted(Budget.Resource.VERIFICATIONS));
      assertThat(result.usage().get(Budget.Resource.VERIFICATIONS)).isEqualTo(2L);
    }
  }
}
