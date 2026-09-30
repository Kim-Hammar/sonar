package se.kth.sonar.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

class GenerationTest {
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
       network 10.1.0.0 0.0.0.1 area 1
      router bgp 65001
       neighbor 10.0.0.1 remote-as 65009
       address-family ipv4
        neighbor 10.0.0.1 activate
       exit-address-family
      """;
  private static final RouterId ONE = RouterId.of("1_as65001_r1");
  private static final RouterId TWO = RouterId.of("2_as65001_r2");
  private static final Dialect DIALECT = Dialect.load("cisco-ios");
  private static ExecutionContext context;
  private static ProgramService programs;
  private static SeedGenerator seeds;
  private static ClosureEngine closure;
  private static Admissibility admissibility;
  private static RepairState state;

  @BeforeAll
  static void build() {
    context = ExecutionContext.open(ParallelismConfig.sequential(), List.of());
    programs = new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
    Bounds bounds = Bounds.defaults();
    seeds = new SeedGenerator(DIALECT.schema(), new ValueDomains(DIALECT.schema(), bounds), bounds);
    closure = new ClosureEngine(programs, seeds, bounds);
    admissibility = new Admissibility(programs, context);
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", R1, "2_as65001_r2.cfg", R2));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(ONE, "GigabitEthernet0/0"),
                    new Topology.Endpoint(TWO, "GigabitEthernet0/0"))));
    Specification target =
        new Specification(
            new ForwardingPredicate.Reachability(ONE, Prefix.parse("10.0.0.2/32")),
            Specification.Status.parse("broken_removed"));
    RepairProblem problem =
        new RepairProblem("pair", snapshot, topology, SpecificationSet.of(List.of(target)));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(target), List.of(), List.of()),
            DataPlaneEvidence.none());
    state = RepairState.initial(problem, verification, programs.model(snapshot, topology));
  }

  @AfterAll
  static void close() {
    context.close();
  }

  @Test
  void seedsAtTheWrongNeighborIncludeTheCorrectRemoteAs() {
    ProgramObject.Ref site = new ProgramObject.Ref("bgp-neighbor", TWO, List.of("10.0.0.1"));
    List<List<EditOperator>> proposals = seeds.seeds(state.program(), state.verification(), site);
    assertThat(proposals).isNotEmpty();
    List<EditOperator> fix =
        proposals.stream()
            .filter(
                seed ->
                    seed.size() == 1
                        && seed.getFirst() instanceof EditOperator.ReplaceSlots r
                        && r.values().equals(Map.of("asn", List.of("65001"))))
            .findFirst()
            .orElseThrow();
    List<Hypothesis> hypotheses =
        closure.close(state.program(), state.verification(), fix, Set.of());
    assertThat(hypotheses).hasSize(1);
    Hypothesis hypothesis = hypotheses.getFirst();
    assertThat(hypothesis.transaction().edits()).hasSize(1);
    assertThat(state.snapshot().patch(hypothesis.transaction()).after().text(TWO).line(9))
        .isEqualTo(" neighbor 10.0.0.1 remote-as 65001");
    assertThat(admissibility.admit(state, hypotheses)).containsExactly(hypothesis);
  }

  @Test
  void theSessionObligationIsCompletedOnEitherSide() {
    RelationInstance session = state.program().instances("bgp-session").getFirst();
    Requirement requirement = session.violated().getFirst();
    List<Hypothesis> hypotheses =
        closure.close(
            state.program(),
            state.verification(),
            List.of(),
            Set.of(new ClosureEngine.Obligation(session, requirement)));
    assertThat(hypotheses).isNotEmpty();
    List<String> texts =
        hypotheses.stream()
            .map(h -> h.transaction().edits().stream().map(Object::toString).toList().toString())
            .toList();
    assertThat(texts).anyMatch(text -> text.contains("neighbor 10.0.0.1 remote-as 65001"));
    assertThat(hypotheses).allMatch(h -> h.restores("bgp-session", requirement));
    List<Hypothesis> admitted = admissibility.admit(state, hypotheses);
    assertThat(admitted).isNotEmpty();
  }

  @Test
  void theLinkObligationOffersAreaAlignmentBothWays() {
    RelationInstance link = state.program().instances("link").getFirst();
    Requirement requirement = link.violated().getFirst();
    List<Hypothesis> hypotheses =
        closure.close(
            state.program(),
            state.verification(),
            List.of(),
            Set.of(new ClosureEngine.Obligation(link, requirement)));
    List<String> texts = hypotheses.stream().map(h -> h.transaction().edits().toString()).toList();
    assertThat(texts)
        .anyMatch(text -> text.contains("ip ospf 1 area 1"))
        .anyMatch(text -> text.contains("ip ospf 1 area 0"));
  }

  @Test
  void deletionsThatLeaveAReferenceOrANeighborWithoutRemoteAsAreRejected() {
    ProgramObject.Ref site = new ProgramObject.Ref("peer-group", ONE, List.of("IBGP"));
    List<List<EditOperator>> proposals = seeds.seeds(state.program(), state.verification(), site);
    List<Hypothesis> hypotheses =
        proposals.stream()
            .map(seed -> Optional.ofNullable(hypothesisOf(seed)))
            .flatMap(Optional::stream)
            .toList();
    List<String> deletions =
        hypotheses.stream()
            .filter(
                h ->
                    h.operators().stream().allMatch(o -> o instanceof EditOperator.DeleteStatement))
            .map(h -> h.transaction().edits().toString())
            .toList();
    assertThat(deletions).hasSize(3);
    List<Hypothesis> admitted = admissibility.admit(state, hypotheses);
    assertThat(admitted)
        .noneMatch(
            h -> h.operators().stream().allMatch(o -> o instanceof EditOperator.DeleteStatement));
  }

  private static Hypothesis hypothesisOf(List<EditOperator> seed) {
    try {
      return Hypothesis.of(state.snapshot(), seed);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
