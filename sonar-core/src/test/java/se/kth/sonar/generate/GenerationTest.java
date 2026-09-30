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
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.BgpIncidenceSet;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.BgpSessionCut;
import se.kth.sonar.state.ProtocolTransfer;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.LineEdit;
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
  private static final String R2_WITHOUT_BGP =
      """
      hostname r2
      interface Loopback0
       ip address 10.0.0.2 255.255.255.255
      interface GigabitEthernet0/0
       ip address 10.1.0.0 255.255.255.254
      router ospf 1
       network 10.1.0.0 0.0.0.1 area 1
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
  void policyCutInsertsTheMissingPermitWithoutReplacingASibling() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_r1.cfg",
                """
                hostname r1
                ip prefix-list EXPORT seq 20 permit 10.200.1.0/24
                """));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref list = new ProgramObject.Ref("prefix-list", ONE, List.of("EXPORT"));

    List<Hypothesis> hypotheses =
        new PolicyCutGenerator(seeds)
            .generate(program, list, Set.of(Prefix.parse("10.200.0.0/24")));

    assertThat(hypotheses).hasSize(1);
    String repaired = snapshot.patch(hypotheses.getFirst().transaction()).after().text(ONE).text();
    assertThat(repaired)
        .contains("ip prefix-list EXPORT seq 20 permit 10.200.1.0/24")
        .contains("ip prefix-list EXPORT seq 21 permit 10.200.0.0/24");
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
  void aMissingProcessIsOpenedBeforeItsSessionRoleIsCreated() {
    Snapshot snapshot =
        Snapshot.of(Map.of("1_as65001_r1.cfg", R1, "2_as65001_r2.cfg", R2_WITHOUT_BGP));
    Topology topology = state.problem().topology();
    RepairProblem problem =
        new RepairProblem("missing-process", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState missing =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    RelationInstance session = missing.program().instances("bgp-session").getFirst();
    Requirement requirement = session.violated().getFirst();

    assertThat(session.routers()).containsExactly(ONE);
    assertThat(session.dependencyRouters()).containsExactly(ONE, TWO);

    List<Hypothesis> hypotheses =
        closure.close(
            missing.program(),
            missing.verification(),
            List.of(),
            Set.of(new ClosureEngine.Obligation(session, requirement)));

    assertThat(hypotheses).isNotEmpty();
    assertThat(
            hypotheses.stream()
                .map(h -> missing.snapshot().patch(h.transaction()).after().text(TWO).text()))
        .anyMatch(
            text ->
                text.contains("router bgp 65001")
                    && text.contains("neighbor 10.0.0.1 remote-as 65001"));
  }

  @Test
  void repeatedReflectorIncidencesFormOneAtomicTypedTransaction() {
    String rr1 = reflector("rr1", "10.0.0.1", "10.0.0.3");
    String rr2 = reflector("rr2", "10.0.0.2", "10.0.0.3");
    String c0 = client("c0", "10.0.0.3", "10.0.0.1", "10.0.0.2");
    String c1 = client("c1", "10.0.0.4", "10.0.0.1", null);
    String c2 = client("c2", "10.0.0.5", "10.0.0.1", null);
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_rr1.cfg", rr1,
                "2_as65001_rr2.cfg", rr2,
                "3_as65001_c0.cfg", c0,
                "4_as65001_c1.cfg", c1,
                "5_as65001_c2.cfg", c2));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    ProgramModel program = programs.model(snapshot, topology);
    List<BgpIncidenceSet> inferred =
        BgpIncidenceSet.infer(
            program, List.of(RouterId.of("4_as65001_c1"), RouterId.of("5_as65001_c2")));
    assertThat(inferred).isNotEmpty();

    BgpIncidenceSet set =
        inferred.stream()
            .filter(item -> item.reflector().router().equals(RouterId.of("2_as65001_rr2")))
            .findFirst()
            .orElseThrow();
    assertThat(set.clients()).hasSize(2);

    List<Hypothesis> hypotheses = new BgpIncidenceGenerator(seeds).generate(program, set);
    assertThat(hypotheses).hasSizeGreaterThanOrEqualTo(3);
    assertThat(
            hypotheses.stream()
                .filter(hypothesis -> hypothesis.routers().size() == 3)
                .map(hypothesis -> snapshot.patch(hypothesis.transaction()).after())
                .map(
                    fixed ->
                        fixed.text(RouterId.of("2_as65001_rr2")).text()
                            + fixed.text(RouterId.of("4_as65001_c1")).text()
                            + fixed.text(RouterId.of("5_as65001_c2")).text()))
        .anyMatch(
            text ->
                text.contains("neighbor 10.0.0.4 peer-group IBGP")
                    && text.contains("neighbor 10.0.0.5 peer-group IBGP")
                    && text.contains("neighbor 10.0.0.2 peer-group RR"));
  }

  @Test
  void aWaypointClientWithoutBgpGetsItsWholeObservedProcessIncidence() {
    RouterId reflector = RouterId.of("2_as65001_rr2");
    RouterId client = RouterId.of("4_as65001_c1");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                reflector + ".cfg",
                reflector("rr2", "10.0.0.2", "10.0.0.3"),
                "3_as65001_c0.cfg",
                client("c0", "10.0.0.3", "10.0.0.2", null),
                client + ".cfg",
                "hostname c1\ninterface Loopback0\n ip address 10.0.0.4 255.255.255.255\n"));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    BgpIncidenceSet incidence =
        BgpIncidenceSet.infer(program, List.of(client), List.of(client)).stream()
            .filter(item -> item.reflector().router().equals(reflector))
            .findFirst()
            .orElseThrow();

    List<Hypothesis> hypotheses = new BgpIncidenceGenerator(seeds).generate(program, incidence);

    assertThat(
            hypotheses.stream()
                .filter(hypothesis -> hypothesis.routers().containsAll(List.of(reflector, client)))
                .map(hypothesis -> snapshot.patch(hypothesis.transaction()).after()))
        .anyMatch(
            repaired ->
                repaired.text(client).text().contains("router bgp 65001")
                    && repaired.text(client).text().contains("bgp router-id 10.0.0.4")
                    && repaired.text(client).text().contains("neighbor RR remote-as 65001")
                    && repaired.text(client).text().contains("neighbor 10.0.0.2 activate")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 peer-group IBGP")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 activate"));
  }

  @Test
  void anIsolationFirstHopFormsOneAtomicReciprocalSessionCut() {
    String second = R2.replace("remote-as 65009", "remote-as 65001");
    Snapshot snapshot = Snapshot.of(Map.of(ONE + ".cfg", R1, TWO + ".cfg", second));
    ForwardingPredicate predicate =
        new ForwardingPredicate.Isolation(ONE, Prefix.parse("10.0.0.2/32"));
    Specification target = new Specification(predicate, Specification.Status.parse("broken_added"));
    Topology topology = state.problem().topology();
    RepairProblem problem =
        new RepairProblem("session-cut", snapshot, topology, SpecificationSet.of(List.of(target)));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(target), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    predicate,
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(ONE, TWO), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState cutting =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    BgpSessionCut cut = BgpSessionCut.infer(cutting).getFirst();

    List<Hypothesis> hypotheses = new BgpSessionCutGenerator().generate(cutting.program(), cut);

    assertThat(hypotheses).hasSize(1);
    Snapshot repaired = snapshot.patch(hypotheses.getFirst().transaction()).after();
    assertThat(repaired.text(ONE).text()).doesNotContain("neighbor 10.0.0.2");
    assertThat(repaired.text(TWO).text()).doesNotContain("neighbor 10.0.0.1");
    assertThat(repaired.text(ONE).text()).contains("neighbor IBGP peer-group");
    assertThat(admissibility.admit(cutting, hypotheses)).containsExactlyElementsOf(hypotheses);
  }

  private static String reflector(String hostname, String address, String client) {
    return """
        hostname %s
        interface Loopback0
         ip address %s 255.255.255.255
        router bgp 65001
         bgp cluster-id %s
         neighbor IBGP peer-group
         neighbor IBGP remote-as 65001
         neighbor %s peer-group IBGP
         address-family ipv4
          neighbor %s activate
          neighbor %s next-hop-self
         exit-address-family
        """
        .formatted(hostname, address, address, client, client, client);
  }

  private static String client(
      String hostname, String address, String firstReflector, String secondReflector) {
    String second =
        secondReflector == null ? "" : " neighbor " + secondReflector + " peer-group RR\n";
    String secondActivation =
        secondReflector == null ? "" : "  neighbor " + secondReflector + " activate\n";
    return """
        hostname %s
        interface Loopback0
         ip address %s 255.255.255.255
        router bgp 65001
         neighbor RR peer-group
         neighbor RR remote-as 65001
         neighbor %s peer-group RR
        %s address-family ipv4
          neighbor %s activate
        %s exit-address-family
        """
        .formatted(hostname, address, firstReflector, second, firstReflector, secondActivation);
  }

  @Test
  void anUnusedPolicyProducesTypedAttachments() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_r1.cfg",
                R1 + "\nroute-map RM_BGP_TO_OSPF permit 10\n",
                "2_as65001_r2.cfg",
                R2));
    RepairProblem problem =
        new RepairProblem(
            "unused-policy",
            snapshot,
            state.problem().topology(),
            state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState unused =
        RepairState.initial(
            problem, verification, programs.model(snapshot, state.problem().topology()));
    RelationInstance liveness =
        unused.program().instances("route-map-liveness").stream()
            .filter(instance -> !instance.satisfied())
            .findFirst()
            .orElseThrow();
    Requirement requirement = liveness.violated().getFirst();

    List<Hypothesis> hypotheses =
        closure.close(
            unused.program(),
            unused.verification(),
            List.of(),
            Set.of(new ClosureEngine.Obligation(liveness, requirement)));

    assertThat(hypotheses).isNotEmpty();
    assertThat(
            hypotheses.stream()
                .map(h -> unused.snapshot().patch(h.transaction()).after().text(ONE).text()))
        .anyMatch(text -> text.contains("redistribute bgp 65001"))
        .anyMatch(text -> text.contains("route-map RM_BGP_TO_OSPF in"));
  }

  @Test
  void aProtocolTransferCombinesItsPossibleDenyWithItsAttachment() {
    String config =
        """
        hostname r1
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        router ospf 1
         router-id 10.0.0.1
        router bgp 65001
         bgp router-id 10.0.0.1
        route-map EXPORT deny 5
         match tag 65100
        route-map EXPORT permit 20
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", config));
    RepairProblem problem =
        new RepairProblem(
            "protocol-transfer",
            snapshot,
            Topology.of(snapshot.routers(), List.of()),
            state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState transferState =
        RepairState.initial(
            problem,
            verification,
            programs.model(snapshot, Topology.of(snapshot.routers(), List.of())));
    ProgramObject.Ref policy = new ProgramObject.Ref("route-map", ONE, List.of("EXPORT"));
    ProgramObject.Ref blocker =
        new ProgramObject.Ref("route-map-entry", ONE, List.of("EXPORT", "5"));
    ProtocolTransfer transfer =
        new ProtocolTransfer(
            policy, transferState.verification().verdict().unresolved(), List.of(blocker));

    List<Hypothesis> hypotheses =
        new ProtocolTransferGenerator(closure)
            .generate(transferState.program(), transferState.verification(), transfer);

    assertThat(
            hypotheses.stream()
                .map(h -> transferState.snapshot().patch(h.transaction()).after().text(ONE).text()))
        .anyMatch(
            text ->
                text.contains("route-map EXPORT permit 5")
                    && text.contains("redistribute bgp 65001")
                    && text.contains("route-map EXPORT"));
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
  void activatingAnIgpLinkCompletesItsMetricConventionOnBothEnds() {
    String first =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.2.0.1 255.255.255.254
         ip router isis 1
         isis metric 10
        router isis 1
         net 49.0001.0000.0000.0001.00
        """;
    String second =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.3.0.1 255.255.255.254
         ip router isis 1
         isis metric 10
        router isis 1
         net 49.0001.0000.0000.0002.00
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", first, "2_as65001_r2.cfg", second));
    Topology topology = state.problem().topology();
    RepairProblem problem =
        new RepairProblem("isis-link", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState missing =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    RelationInstance link = missing.program().instances("link").getFirst();
    Requirement activation =
        link.violated().stream()
            .filter(requirement -> requirement.text().contains("exists a.igp-enabled"))
            .findFirst()
            .orElseThrow();

    List<Hypothesis> hypotheses =
        closure.close(
            missing.program(),
            missing.verification(),
            List.of(),
            Set.of(new ClosureEngine.Obligation(link, activation)));

    assertThat(
            hypotheses.stream()
                .map(hypothesis -> missing.snapshot().patch(hypothesis.transaction()).after()))
        .anyMatch(
            repaired ->
                repaired.text(ONE).text().contains(" ip router isis 1\n isis metric 10")
                    && repaired.text(TWO).text().contains(" ip router isis 1\n isis metric 10"));
  }

  @Test
  void aCollidingLoopbackRenameSplitsDuplicatedSessionUsesAtomically() {
    String first =
        """
        hostname r1
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        router ospf 1
         router-id 10.0.0.2
         network 10.0.0.1 0.0.0.0 area 0
        router bgp 65001
         bgp router-id 10.0.0.2
         neighbor IBGP peer-group
         neighbor IBGP remote-as 65001
         neighbor 10.0.0.1 peer-group IBGP
         address-family ipv4
          neighbor 10.0.0.1 activate
         exit-address-family
        """;
    String second =
        """
        hostname r2
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        router ospf 1
         router-id 10.0.0.1
        router bgp 65001
         bgp router-id 10.0.0.1
         neighbor IBGP peer-group
         neighbor IBGP remote-as 65001
         neighbor 10.0.0.1 peer-group IBGP
         address-family ipv4
          neighbor 10.0.0.1 activate
         exit-address-family
        """;
    String third =
        """
        hostname r3
        interface Loopback0
         ip address 10.0.0.3 255.255.255.255
        router bgp 65001
         bgp router-id 10.0.0.3
         neighbor IBGP peer-group
         neighbor IBGP remote-as 65001
         neighbor 10.0.0.1 peer-group IBGP
         neighbor 10.0.0.1 peer-group IBGP
         address-family ipv4
          neighbor 10.0.0.1 activate
          neighbor 10.0.0.1 activate
         exit-address-family
        """;
    RouterId three = RouterId.of("3_as65001_r3");
    Snapshot snapshot =
        Snapshot.of(Map.of(ONE + ".cfg", first, TWO + ".cfg", second, three + ".cfg", third));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    RepairProblem problem =
        new RepairProblem("identity", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState colliding =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    RelationInstance identity =
        colliding.program().instances("router-identity").stream()
            .filter(instance -> instance.routers().contains(ONE))
            .filter(instance -> !instance.satisfied())
            .findFirst()
            .orElseThrow();
    Set<ClosureEngine.Obligation> obligations =
        identity.violated().stream()
            .filter(Requirement::signals)
            .map(requirement -> new ClosureEngine.Obligation(identity, requirement))
            .collect(java.util.stream.Collectors.toSet());
    List<Hypothesis> base =
        closure.close(colliding.program(), colliding.verification(), List.of(), obligations);

    List<Hypothesis> refactored = IdentityRefactoring.complete(colliding.program(), base);

    assertThat(
            refactored.stream().map(hypothesis -> snapshot.patch(hypothesis.transaction()).after()))
        .anyMatch(
            repaired ->
                repaired.text(ONE).text().contains("ip address 10.0.0.2 255.255.255.255")
                    && repaired.text(ONE).text().contains("network 10.0.0.2 0.0.0.0 area 0")
                    && repaired.text(TWO).text().contains("neighbor 10.0.0.2 peer-group IBGP")
                    && repaired
                            .text(three)
                            .text()
                            .lines()
                            .filter(line -> line.contains("neighbor 10.0.0.2"))
                            .count()
                        == 2
                    && repaired
                            .text(three)
                            .text()
                            .lines()
                            .filter(line -> line.contains("neighbor 10.0.0.1"))
                            .count()
                        == 2);
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

  @Test
  void anInterfaceOnlyStaticRouteMustLeadToTheInterfacesOwnSubnet() {
    Hypothesis remoteToLoopback =
        new Hypothesis(
            Transaction.of(
                state.snapshot(),
                List.of(
                    new LineEdit.Append(
                        ONE, List.of("ip route 10.9.0.0 255.255.255.0 Loopback0")))),
            List.of());
    Hypothesis ownLoopback =
        new Hypothesis(
            Transaction.of(
                state.snapshot(),
                List.of(
                    new LineEdit.Append(
                        ONE, List.of("ip route 10.0.0.1 255.255.255.255 Loopback0")))),
            List.of());

    assertThat(admissibility.admit(state, List.of(remoteToLoopback, ownLoopback)))
        .containsExactly(ownLoopback);
  }

  @Test
  void bgpRedistributionMustNameTheLocalBgpProcess() {
    String config =
        """
        hostname r1
        router ospf 1
         router-id 10.0.0.1
        router bgp 65001
         address-family ipv4
         exit-address-family
        route-map EXPORT permit 10
        """;
    Snapshot snapshot = Snapshot.of(Map.of(ONE + ".cfg", config));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    RepairProblem problem =
        new RepairProblem("redistribution", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState redistribution =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    Hypothesis wrongProcess =
        new Hypothesis(
            Transaction.of(
                snapshot,
                List.of(
                    new LineEdit.InsertAfter(
                        ONE, 3, List.of(" redistribute bgp 65002 subnets route-map EXPORT")))),
            List.of());
    Hypothesis localProcess =
        new Hypothesis(
            Transaction.of(
                snapshot,
                List.of(
                    new LineEdit.InsertAfter(
                        ONE, 3, List.of(" redistribute bgp 65001 subnets route-map EXPORT")))),
            List.of());

    assertThat(admissibility.admit(redistribution, List.of(wrongProcess, localProcess)))
        .containsExactly(localProcess);
  }

  private static Hypothesis hypothesisOf(List<EditOperator> seed) {
    try {
      return Hypothesis.of(state.snapshot(), seed);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
