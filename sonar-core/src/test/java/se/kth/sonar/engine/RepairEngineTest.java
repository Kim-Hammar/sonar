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
import se.kth.sonar.spec.Verdict;
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
            new Adoption(verifier, programs)));
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
    assertThat(result.adopted()).hasSizeBetween(1, 3);
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
      Adoption.Adopted adopted =
          new Adoption(verifier, programs).adopt(state, verified).orElseThrow();
      assertThat(adopted.hypotheses()).containsExactlyInAnyOrder(session, area);
      assertThat(adopted.verification().verdict().solved()).isTrue();
      assertThat(new Adoption(verifier, programs).adopt(state, List.of(verified.getFirst())))
          .isEmpty();

      List<EditOperator> renamed = new ArrayList<>(area.operators());
      renamed.addAll(replace(state, 1, "name", "r2b").operators());
      Hypothesis padded = Hypothesis.of(state.snapshot(), renamed);
      verified.add(
          new Adoption.Verified(
              padded,
              verifier.verify(
                  state.snapshot().patch(padded.transaction()).after(), problem.specifications())));
      assertThat(new Adoption(verifier, programs).adopt(state, verified).orElseThrow().hypotheses())
          .as("the renaming resolves nothing that the area alone leaves unresolved")
          .containsExactlyInAnyOrder(session, area);

      Hypothesis renaming = replace(state, 1, "name", "r2b");
      List<Adoption.Verified> extended = new ArrayList<>();
      for (Hypothesis hypothesis : List.of(renaming, padded)) {
        Snapshot snapshot = state.snapshot().patch(hypothesis.transaction()).after();
        extended.add(
            new Adoption.Verified(hypothesis, verifier.verify(snapshot, problem.specifications())));
      }
      assertThat(new Adoption(verifier, programs).adopt(state, extended).orElseThrow().hypotheses())
          .as("the renaming alone changes nothing, so the area is adopted without it")
          .extracting(Hypothesis::id)
          .containsExactly(area.id());

      List<EditOperator> everything = new ArrayList<>(renaming.operators());
      everything.addAll(session.operators());
      everything.addAll(area.operators());
      Hypothesis overdone = Hypothesis.of(state.snapshot(), everything);
      Adoption.Verified breaking =
          new Adoption.Verified(
              overdone,
              new Verification(
                  state.snapshot().patch(overdone.transaction()).after().id(),
                  new Verdict(List.of(), List.of(SESSION, ADJACENCY), List.of(INTACT)),
                  DataPlaneEvidence.none()));
      assertThat(new Adoption(verifier, programs).part(state, breaking))
          .as("a candidate that resolves everything and breaks something is tried by its parts")
          .map(part -> part.hypothesis().id())
          .contains(session.id());
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
                    new Verdict(List.of(ADJACENCY), List.of(SESSION), List.of()),
                    DataPlaneEvidence.none())));
      }

      Adoption.Adopted adopted = new Adoption(verifier, programs).adopt(state, tied).orElseThrow();

      assertThat(adopted.hypotheses()).containsExactly(decisionOrder.getFirst());
    }
  }

  @Test
  void adoptionSetsAsideAnImprovementThatBreaksARelationAnotherKeeps() {
    RepairProblem problem = problem();
    Specification third =
        new Specification(
            new ForwardingPredicate.Reachability(TWO, Prefix.parse("10.1.0.0/31")),
            Specification.Status.parse("broken_removed"));
    try (ResourcePool<DataPlaneEngine> engines =
        ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close)) {
      ProgramService programs =
          new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
      VerificationService verifier = new VerificationService(engines, context);
      RepairState state =
          RepairState.initial(
              problem,
              new Verification(
                  problem.initial().id(),
                  new Verdict(List.of(SESSION, ADJACENCY, third), List.of(), List.of()),
                  DataPlaneEvidence.none()),
              programs.model(problem.initial(), problem.topology()));
      Hypothesis repair = replace(state, 8, "area", "0");
      Hypothesis identity = replace(state, 10, "id", "10.0.0.9");
      List<EditOperator> both = new ArrayList<>(repair.operators());
      both.addAll(identity.operators());
      Hypothesis extended = Hypothesis.of(state.snapshot(), both);
      java.util.function.BiFunction<Hypothesis, List<Specification>, Adoption.Verified> verified =
          (hypothesis, unresolved) ->
              new Adoption.Verified(
                  hypothesis,
                  new Verification(
                      state.snapshot().patch(hypothesis.transaction()).after().id(),
                      new Verdict(unresolved, List.of(), List.of()),
                      DataPlaneEvidence.none()));
      Adoption adoption = new Adoption(verifier, programs);

      assertThat(
              adoption
                  .adopt(
                      state,
                      List.of(
                          verified.apply(extended, List.of(SESSION)),
                          verified.apply(repair, List.of(SESSION, third))))
                  .orElseThrow()
                  .hypotheses())
          .as("the extension gives r2 a BGP identity other than its loopback, which it had")
          .containsExactly(repair);
      assertThat(
              adoption
                  .adopt(
                      state,
                      List.of(
                          verified.apply(identity, List.of(SESSION)),
                          verified.apply(repair, List.of(SESSION, third))))
                  .orElseThrow()
                  .hypotheses())
          .as("unrelated candidates: the one that leaves less unresolved, alone")
          .containsExactly(identity);
      assertThat(
              adoption
                  .adopt(
                      state,
                      List.of(
                          verified.apply(extended, List.of()),
                          verified.apply(repair, List.of(SESSION, third))))
                  .orElseThrow()
                  .hypotheses())
          .containsExactly(extended);
    }
  }

  @Test
  void anInertEditThatCompletesARenameIsKept() {
    RepairProblem problem = problem();
    try (ResourcePool<DataPlaneEngine> engines =
        ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close)) {
      ProgramService programs =
          new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
      VerificationService verifier = new VerificationService(engines, context);
      RepairState state =
          RepairState.initial(
              problem,
              verifier.verify(problem.initial(), problem.specifications()),
              programs.model(problem.initial(), problem.topology()));
      List<EditOperator> references = new ArrayList<>();
      references.addAll(operator(state, ONE, 14, "peer", "10.0.0.9").operators());
      references.addAll(operator(state, ONE, 16, "peer", "10.0.0.9").operators());
      Hypothesis referenced = Hypothesis.of(state.snapshot(), references);
      List<EditOperator> whole = new ArrayList<>(replace(state, 8, "area", "0").operators());
      whole.addAll(operator(state, TWO, 3, "address", "10.0.0.9").operators());
      whole.addAll(references);
      Hypothesis renamed = Hypothesis.of(state.snapshot(), whole);
      List<Adoption.Verified> verified = new ArrayList<>();
      for (Hypothesis hypothesis : List.of(referenced, renamed)) {
        Snapshot snapshot = state.snapshot().patch(hypothesis.transaction()).after();
        verified.add(
            new Adoption.Verified(hypothesis, verifier.verify(snapshot, problem.specifications())));
      }
      assertThat(verified.getFirst().verification().verdict().fixed()).isEmpty();

      assertThat(new Adoption(verifier, programs).adopt(state, verified).orElseThrow().hypotheses())
          .as("without r1's references to it, r2's new loopback would leave r1's session dangling")
          .containsExactly(renamed);
    }
  }

  private static Hypothesis operator(
      RepairState state, RouterId router, int line, String slot, String value) {
    return Hypothesis.of(
        state.snapshot(),
        List.of(
            new EditOperator.ReplaceSlots(
                state.program().statements(router).stream()
                    .filter(statement -> statement.span().first() == line)
                    .findFirst()
                    .orElseThrow(),
                slot,
                List.of(value),
                new Provenance.Existing())));
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
  void anImprovementVerifiedBeforeTheBudgetRanOutIsAdopted() throws Exception {
    RepairProblem problem = problem();
    for (int limit = 2; limit <= 40; limit++) {
      List<TraceRecord> records = new CopyOnWriteArrayList<>();
      Budget budget = Budget.builder().limit(Budget.Resource.VERIFICATIONS, limit).build();
      RunScope scope = new RunScope("pair", budget, 1, new Cancellation());
      RepairResult result;
      try (ResourcePool<DataPlaneEngine> engines =
          ResourcePool.of(1, ScriptedDataPlane::new, DataPlaneEngine::close)) {
        RepairEngine engine = engine(engines);
        result =
            ScopedValue.where(RunScope.CURRENT, scope)
                .where(TraceSink.CURRENT, (TraceSink) records::add)
                .call(() -> engine.repair(problem));
      }
      int best =
          records.stream()
              .map(TraceRecord::event)
              .filter(TraceEvent.Tried.class::isInstance)
              .map(TraceEvent.Tried.class::cast)
              .filter(tried -> tried.change() == se.kth.sonar.spec.Verdict.Change.IMPROVEMENT)
              .mapToInt(tried -> tried.verification().verdict().unresolved().size())
              .min()
              .orElse(Integer.MAX_VALUE);
      assertThat(result.verdict().unresolved().size())
          .as("verification budget %d", limit)
          .isLessThanOrEqualTo(best);
    }
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
