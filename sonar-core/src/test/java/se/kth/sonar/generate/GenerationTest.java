package se.kth.sonar.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.AgreementOutlier;
import se.kth.sonar.program.BgpIncidenceSet;
import se.kth.sonar.program.BgpMesh;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.search.SiteRegion;
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
import se.kth.sonar.text.SourceSpan;
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
  void aDiscriminatedStatementCanBeInsertedBesideItsSibling() {
    String config =
        """
        hostname r1
        router bgp 65001
         neighbor 10.0.0.2 remote-as 65002
         address-family ipv4
          neighbor 10.0.0.2 activate
          neighbor 10.0.0.2 route-map IMPORT in
         exit-address-family
        route-map IMPORT permit 10
        route-map EXPORT permit 10
        """;
    Snapshot snapshot = Snapshot.of(Map.of(ONE + ".cfg", config));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());
    ProgramObject.Ref neighbor = new ProgramObject.Ref("bgp-neighbor", ONE, List.of("10.0.0.2"));

    List<List<EditOperator>> proposals = seeds.seeds(program, verification, neighbor);

    assertThat(proposals)
        .anyMatch(
            proposal ->
                proposal.stream()
                    .anyMatch(
                        operator ->
                            operator instanceof EditOperator.InsertStatement insertion
                                && insertion
                                    .text()
                                    .equals("neighbor 10.0.0.2 route-map EXPORT out")));
  }

  @Test
  void aRootStatementIsInsertedAfterUnmatchedTrailingText() {
    String config =
        """
        hostname r1
        ip prefix-list EXPORT seq 10 permit 10.200.1.0/24
        router bgp 65001
         address-family ipv4
          network 10.200.1.0 mask 255.255.255.0
         exit-address-family
        trailing text outside the grammar
        """;
    Snapshot snapshot = Snapshot.of(Map.of(ONE + ".cfg", config));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref list = new ProgramObject.Ref("prefix-list", ONE, List.of("EXPORT"));

    List<EditOperator> operators =
        seeds.insert(
            program,
            list,
            "prefix-list-entry",
            Map.of(
                "name",
                List.of("EXPORT"),
                "sequence",
                List.of("20"),
                "action",
                List.of("permit"),
                "prefix",
                List.of("10.200.2.0/24")),
            new Provenance.Inferred("test"));

    assertThat(operators).hasSize(1);
    assertThat(operators.getFirst().lower())
        .isEqualTo(
            new LineEdit.InsertAfter(
                ONE, 7, List.of("ip prefix-list EXPORT seq 20 permit 10.200.2.0/24")));
  }

  @Test
  void aNeighborPolicyAttachmentPairCanBePermutedAtomically() {
    String config =
        """
        hostname r1
        router bgp 65001
         neighbor 10.0.0.2 remote-as 65002
         address-family ipv4
          neighbor 10.0.0.2 activate
          neighbor 10.0.0.2 route-map EXPORT in
          neighbor 10.0.0.2 route-map IMPORT out
         exit-address-family
        route-map IMPORT permit 10
        route-map EXPORT permit 10
        """;
    Snapshot snapshot = Snapshot.of(Map.of(ONE + ".cfg", config));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref neighbor = new ProgramObject.Ref("bgp-neighbor", ONE, List.of("10.0.0.2"));

    List<Hypothesis> hypotheses = PolicyAttachmentPermutation.generate(program, neighbor);

    assertThat(hypotheses).hasSize(1);
    String repaired = snapshot.patch(hypotheses.getFirst().transaction()).after().text(ONE).text();
    assertThat(repaired)
        .contains("neighbor 10.0.0.2 route-map IMPORT in")
        .contains("neighbor 10.0.0.2 route-map EXPORT out")
        .doesNotContain("neighbor 10.0.0.2 route-map EXPORT in")
        .doesNotContain("neighbor 10.0.0.2 route-map IMPORT out");
  }

  @Test
  void aDirectBgpPolicyRepairClosesOverItsFirstMatchTransportBlocker() {
    RouterId firstId = RouterId.of("1_as65001_r1");
    RouterId secondId = RouterId.of("2_as65002_r2");
    String first =
        """
        hostname r1
        ip access-list extended EDGE_IN
         5 deny ip any any
         10 permit ip any any
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
         ip access-group EDGE_IN in
        router bgp 65001
         neighbor 10.1.0.1 remote-as 65002
         address-family ipv4
          neighbor 10.1.0.1 activate
         exit-address-family
        """;
    String second =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        router bgp 65002
         neighbor 10.1.0.0 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.0 activate
          neighbor 10.1.0.0 route-map EXPORT in
          neighbor 10.1.0.0 route-map IMPORT out
         exit-address-family
        route-map IMPORT permit 10
        route-map EXPORT permit 10
        """;
    Snapshot snapshot = Snapshot.of(Map.of(firstId + ".cfg", first, secondId + ".cfg", second));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(firstId, "GigabitEthernet0/0"),
                    new Topology.Endpoint(secondId, "GigabitEthernet0/0"))));
    ProgramModel program = programs.model(snapshot, topology);
    ProgramObject.Ref neighbor =
        new ProgramObject.Ref("bgp-neighbor", secondId, List.of("10.1.0.0"));
    ProgramObject.Ref acl = new ProgramObject.Ref("acl", firstId, List.of("EDGE_IN"));
    List<Hypothesis> policies = PolicyAttachmentPermutation.generate(program, neighbor);

    assertThat(InterdomainTransferClosure.relatedNeighbors(program, acl)).contains(neighbor);
    List<Hypothesis> closed = InterdomainTransferClosure.close(program, neighbor, policies);

    assertThat(closed).hasSize(1);
    String repairedFirst =
        snapshot.patch(closed.getFirst().transaction()).after().text(firstId).text();
    String repairedSecond =
        snapshot.patch(closed.getFirst().transaction()).after().text(secondId).text();
    assertThat(repairedFirst).doesNotContain("5 deny ip any any").contains("10 permit ip any any");
    assertThat(repairedSecond)
        .contains("neighbor 10.1.0.0 route-map IMPORT in")
        .contains("neighbor 10.1.0.0 route-map EXPORT out");
  }

  @Test
  void aLinkRepairClosesOverTheFirstMatchBlockerOnTheLink() {
    RouterId firstId = RouterId.of("1_as65001_r1");
    RouterId secondId = RouterId.of("2_as65002_r2");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                firstId + ".cfg",
                """
                hostname r1
                ip access-list extended EDGE_IN
                 5 deny ip any any
                 10 permit ip any any
                interface GigabitEthernet0/0
                 ip address 10.1.0.8 255.255.255.254
                 ip access-group EDGE_IN in
                """,
                secondId + ".cfg",
                """
                hostname r2
                interface GigabitEthernet0/0
                 ip address 10.1.0.1 255.255.255.254
                """));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(firstId, "GigabitEthernet0/0"),
                    new Topology.Endpoint(secondId, "GigabitEthernet0/0"))));
    ProgramModel program = programs.model(snapshot, topology);
    RelationInstance link = program.instances("link").getFirst();
    assertThat(link.violated())
        .extracting(Requirement::text)
        .contains("hard equal a.subnet b.subnet");
    Hypothesis readdressed =
        Hypothesis.of(
            snapshot,
            List.of(
                new EditOperator.ReplaceSlots(
                    program.statements(firstId).stream()
                        .filter(statement -> statement.text().startsWith("ip address"))
                        .findFirst()
                        .orElseThrow(),
                    "address",
                    List.of("10.1.0.0"),
                    new Provenance.Existing())));

    List<Hypothesis> closed =
        InterdomainTransferClosure.closeLink(program, link, List.of(readdressed));

    assertThat(closed).hasSize(1);
    assertThat(snapshot.patch(closed.getFirst().transaction()).after().text(firstId).text())
        .contains("ip address 10.1.0.0 255.255.255.254")
        .doesNotContain("5 deny ip any any")
        .contains("10 permit ip any any");
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
  void policyCutAtomicallyOpensATransformBearingDenyAndItsMissingOperand() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_r1.cfg",
                """
                hostname r1
                ip prefix-list EXPORT seq 20 permit 10.200.1.0/24
                route-map OUT deny 100
                 match ip address prefix-list EXPORT
                 set metric 9
                """));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref list = new ProgramObject.Ref("prefix-list", ONE, List.of("EXPORT"));

    List<Hypothesis> hypotheses =
        new PolicyCutGenerator(seeds)
            .generate(program, list, Set.of(Prefix.parse("10.200.0.0/24")));

    assertThat(
            hypotheses.stream()
                .map(
                    hypothesis ->
                        snapshot.patch(hypothesis.transaction()).after().text(ONE).text()))
        .anyMatch(
            repaired ->
                repaired.contains("ip prefix-list EXPORT seq 21 permit 10.200.0.0/24")
                    && repaired.contains("route-map OUT permit 100"));
  }

  @Test
  void policyCutCompletionContainsEveryMissingOperand() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_r1.cfg",
                """
                hostname r1
                ip prefix-list EXPORT seq 30 permit 10.200.2.0/24
                """));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref list = new ProgramObject.Ref("prefix-list", ONE, List.of("EXPORT"));

    List<Hypothesis> hypotheses =
        new PolicyCutGenerator(seeds)
            .complete(
                program,
                list,
                Set.of(Prefix.parse("10.200.0.0/24"), Prefix.parse("10.200.1.0/24")));

    assertThat(hypotheses).hasSize(1);
    String repaired = snapshot.patch(hypotheses.getFirst().transaction()).after().text(ONE).text();
    assertThat(repaired)
        .contains("permit 10.200.0.0/24")
        .contains("permit 10.200.1.0/24")
        .contains("permit 10.200.2.0/24");
  }

  @Test
  void aBgpPolicyPathCompletesOneMissingAdvertisementWithItsAttachment() {
    RouterId sender = RouterId.of("1_as65006_sender");
    RouterId receiver = RouterId.of("2_as65001_receiver");
    Prefix destination = Prefix.parse("10.200.0.0/24");
    String senderConfig =
        """
        hostname sender
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        ip prefix-list EXPORT seq 20 permit 10.200.1.0/24
        route-map OUT permit 100
         match ip address prefix-list EXPORT
        router bgp 65006
         neighbor 10.1.0.1 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.1 activate
         exit-address-family
        """;
    String receiverConfig =
        """
        hostname receiver
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        route-map IN permit 100
        router bgp 65001
         neighbor 10.1.0.0 remote-as 65006
         address-family ipv4
          neighbor 10.1.0.0 activate
          neighbor 10.1.0.0 route-map IN in
         exit-address-family
        """;
    Snapshot snapshot =
        Snapshot.of(Map.of(sender + ".cfg", senderConfig, receiver + ".cfg", receiverConfig));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(sender, "GigabitEthernet0/0"),
                    new Topology.Endpoint(receiver, "GigabitEthernet0/0"))));
    ProgramModel program = programs.model(snapshot, topology);
    Specification missing =
        new Specification(
            new ForwardingPredicate.Reachability(receiver, destination),
            Specification.Status.parse("broken_removed"));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(missing), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    missing.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(receiver), DataPlaneEvidence.Disposition.NO_ROUTE))),
                Map.of(destination, Set.of(sender)),
                Map.of()));
    ProgramObject.Ref neighbor =
        new ProgramObject.Ref("bgp-neighbor", receiver, List.of("10.1.0.0"));

    List<Hypothesis> hypotheses =
        new BgpPolicyPathGenerator(seeds).generate(program, verification, neighbor);

    assertThat(hypotheses)
        .anyMatch(
            hypothesis -> {
              String repaired =
                  snapshot.patch(hypothesis.transaction()).after().text(sender).text();
              return repaired.contains("ip prefix-list EXPORT seq 21 permit 10.200.0.0/24")
                  && repaired.contains("neighbor 10.1.0.1 route-map OUT out");
            });
  }

  @Test
  void aForbiddenFlowCutPreservesAnAlreadyFixedAdvertisementAtomically() {
    RouterId local = RouterId.of("1_as65006_local");
    RouterId remote = RouterId.of("2_as65001_remote");
    Prefix preserved = Prefix.parse("10.200.3.0/24");
    Prefix forbidden = Prefix.parse("10.200.7.0/24");
    String localConfig =
        """
        hostname local
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        ip prefix-list BLOCK seq 10 permit 10.200.7.0/24
        ip prefix-list EXPORT seq 20 permit 10.200.4.0/24
        route-map OUT deny 10
         match ip address prefix-list BLOCK
        route-map OUT permit 100
         match ip address prefix-list EXPORT
        router bgp 65006
         neighbor 10.1.0.1 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.1 activate
         exit-address-family
        """;
    String remoteConfig =
        """
        hostname remote
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        router bgp 65001
         neighbor 10.1.0.0 remote-as 65006
         address-family ipv4
          neighbor 10.1.0.0 activate
         exit-address-family
        """;
    Snapshot snapshot =
        Snapshot.of(Map.of(local + ".cfg", localConfig, remote + ".cfg", remoteConfig));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(local, "GigabitEthernet0/0"),
                    new Topology.Endpoint(remote, "GigabitEthernet0/0"))));
    ProgramModel program = programs.model(snapshot, topology);
    Specification fixed =
        new Specification(
            new ForwardingPredicate.Reachability(remote, preserved),
            Specification.Status.parse("broken_removed"));
    Specification target =
        new Specification(
            new ForwardingPredicate.Isolation(remote, forbidden),
            Specification.Status.parse("broken_added"));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(target), List.of(fixed), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    fixed.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(remote, local), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    ProgramObject.Ref neighbor = new ProgramObject.Ref("bgp-neighbor", local, List.of("10.1.0.1"));
    ProgramObject.Ref policy = new ProgramObject.Ref("route-map", local, List.of("OUT"));
    ProtocolTransfer transfer =
        new ProtocolTransfer(
            policy,
            List.of(target),
            List.of(),
            Optional.of(new ProtocolTransfer.Attachment(neighbor, "out")));

    List<Hypothesis> hypotheses =
        new ProtocolTransferGenerator(closure, seeds).generate(program, verification, transfer);

    assertThat(hypotheses)
        .anyMatch(
            hypothesis -> {
              Snapshot repaired = snapshot.patch(hypothesis.transaction()).after();
              String text = repaired.text(local).text();
              ProgramModel repairedProgram =
                  programs.model(repaired, topology, Optional.of(program));
              return text.contains("ip prefix-list EXPORT seq 21 permit 10.200.3.0/24")
                  && text.contains("neighbor 10.1.0.1 route-map OUT out")
                  && repairedProgram.statements(local).stream()
                      .filter(
                          statement ->
                              statement
                                      .text()
                                      .equals("ip prefix-list EXPORT seq 21 permit 10.200.3.0/24")
                                  || statement.text().equals("neighbor 10.1.0.1 route-map OUT out"))
                      .allMatch(Statement::matched);
            });
  }

  @Test
  void aStaticRouteOriginationObligationInsertsTheMissingBgpNetwork() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_r1.cfg",
                """
                hostname r1
                interface Loopback100
                 shutdown
                 ip address 10.200.0.1 255.255.255.0
                router bgp 65001
                 address-family ipv4
                  network 10.200.1.0 mask 255.255.255.0
                 exit-address-family
                ip route 10.200.0.0 255.255.255.0 Loopback100
                """));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());
    RelationInstance origination =
        program.violated().stream()
            .filter(instance -> instance.relation().equals("static-origination"))
            .findFirst()
            .orElseThrow();
    Requirement requirement = origination.violated().getFirst();

    List<Hypothesis> hypotheses =
        closure.close(
            program,
            verification,
            List.of(),
            Set.of(new ClosureEngine.Obligation(origination, requirement)));

    assertThat(
            hypotheses.stream()
                .map(
                    hypothesis ->
                        snapshot.patch(hypothesis.transaction()).after().text(ONE).text()))
        .anyMatch(text -> text.contains("network 10.200.0.0 mask 255.255.255.0"));
    RepairProblem problem =
        new RepairProblem(
            "origin",
            snapshot,
            Topology.of(snapshot.routers(), List.of()),
            SpecificationSet.of(List.of()));
    RepairState originState = RepairState.initial(problem, verification, program);
    Hypothesis exact =
        hypotheses.stream()
            .filter(
                hypothesis ->
                    snapshot
                        .patch(hypothesis.transaction())
                        .after()
                        .text(ONE)
                        .text()
                        .contains("network 10.200.0.0 mask 255.255.255.0"))
            .findFirst()
            .orElseThrow();
    Snapshot repaired = snapshot.patch(exact.transaction()).after();
    ProgramModel repairedProgram =
        programs.model(repaired, Topology.of(snapshot.routers(), List.of()), Optional.of(program));
    assertThat(admissibility.admit(originState, hypotheses))
        .as(
            "transaction=%s; text=%s; changed=%s; violations=%s; dangling=%s",
            exact.transaction().edits(),
            repaired.text(ONE).text(),
            ProgramModel.changedObjects(program, repairedProgram, exact.routers()),
            repairedProgram.violated(),
            repairedProgram.dangling())
        .anyMatch(
            hypothesis ->
                snapshot
                    .patch(hypothesis.transaction())
                    .after()
                    .text(ONE)
                    .text()
                    .contains("network 10.200.0.0 mask 255.255.255.0"));
  }

  @Test
  void aStubServiceRelationMaterialisesTheWholeMissingOrigination() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_r1.cfg",
                """
                hostname r1
                interface Loopback100
                 ip address 10.200.0.1 255.255.255.0
                interface Loopback101
                 ip address 10.200.1.1 255.255.255.0
                router bgp 65001
                 address-family ipv4
                  network 10.200.1.0 mask 255.255.255.0
                 exit-address-family
                ip route 10.200.1.0 255.255.255.0 Loopback101
                """));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    ProgramModel program = programs.model(snapshot, topology);
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());
    RelationInstance service =
        program.violated().stream()
            .filter(instance -> instance.relation().equals("service-origination"))
            .filter(
                instance -> instance.roles().get("service").key().equals(List.of("Loopback100")))
            .findFirst()
            .orElseThrow();

    Set<ClosureEngine.Obligation> obligations = new java.util.LinkedHashSet<>();
    service
        .violated()
        .forEach(
            requirement -> obligations.add(new ClosureEngine.Obligation(service, requirement)));
    List<Hypothesis> hypotheses = closure.close(program, verification, List.of(), obligations);

    assertThat(
            hypotheses.stream()
                .map(
                    hypothesis ->
                        snapshot.patch(hypothesis.transaction()).after().text(ONE).text()))
        .anyMatch(
            text ->
                !text.contains(" shutdown")
                    && text.contains("ip route 10.200.0.0 255.255.255.0 Loopback100")
                    && text.contains("network 10.200.0.0 mask 255.255.255.0"));
  }

  @Test
  void seedsAtABlockObjectCanDeleteAnAdmissibleMatchedChildStatement() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_r1.cfg",
                """
                hostname r1
                ip access-list extended EDGE_OUT
                 5 deny ip any any
                 10 permit ip host 10.0.0.1 any
                """));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref acl = new ProgramObject.Ref("acl", ONE, List.of("EDGE_OUT"));
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());

    List<List<EditOperator>> proposals = seeds.seeds(program, verification, acl);

    assertThat(proposals)
        .anyMatch(
            proposal ->
                proposal.size() == 1
                    && proposal.getFirst() instanceof EditOperator.DeleteStatement deletion
                    && deletion.statement().text().equals("5 deny ip any any"));
    List<Hypothesis> deletions =
        proposals.stream()
            .filter(
                proposal ->
                    proposal.size() == 1
                        && proposal.getFirst() instanceof EditOperator.DeleteStatement deletion
                        && deletion.statement().text().equals("5 deny ip any any"))
            .flatMap(proposal -> closure.close(program, verification, proposal, Set.of()).stream())
            .toList();
    RepairProblem problem =
        new RepairProblem(
            "acl",
            snapshot,
            Topology.of(snapshot.routers(), List.of()),
            SpecificationSet.of(List.of()));
    RepairState aclState =
        RepairState.initial(
            problem,
            verification,
            programs.model(snapshot, Topology.of(snapshot.routers(), List.of())));

    assertThat(admissibility.admit(aclState, deletions)).isNotEmpty();
    assertThat(deletions).allMatch(deletion -> deletion.support() == ValueSupport.OBSERVED);

    SearchSpaceBuilder spaces =
        new SearchSpaceBuilder(
            seeds,
            closure,
            admissibility,
            new SearchSpace.Capacity(16, 256),
            Bounds.defaults(),
            context);
    Region expanded =
        spaces.expand(
            new SiteRegion(
                new Region.Id("site:" + acl),
                acl,
                List.of(),
                Map.of(ONE, snapshot.text(ONE).digest()),
                new Region.Hypotheses.Deferred(8)),
            aclState);
    assertThat(((Region.Hypotheses.Admitted) expanded.hypotheses()).list())
        .filteredOn(hypothesis -> hypothesis.id().equals(deletions.getFirst().id()))
        .as("the deletion the first-match analysis also derives")
        .singleElement()
        .matches(hypothesis -> hypothesis.support() == ValueSupport.EVIDENCED);
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

    RouterId doubtful = RouterId.of("5_as65007_c2");
    Snapshot renamed =
        Snapshot.of(
            Map.of(
                "1_as65001_rr1.cfg",
                rr1,
                "2_as65001_rr2.cfg",
                rr2,
                "3_as65001_c0.cfg",
                c0,
                "4_as65001_c1.cfg",
                c1,
                doubtful + ".cfg",
                c2));
    assertThat(
            BgpIncidenceSet.infer(
                programs.model(renamed, Topology.of(renamed.routers(), List.of())),
                List.of(RouterId.of("4_as65001_c1"), doubtful)))
        .allSatisfy(
            item ->
                assertThat(item.objects())
                    .as("a router whose BGP process names another AS than its design")
                    .extracting(ProgramObject.Ref::router)
                    .doesNotContain(doubtful));
  }

  @Test
  void aWhollyMissingSingleReflectorIncidenceIsOneAtomicTypedTransaction() {
    RouterId reflector = RouterId.of("2_as65001_rr2");
    RouterId client = RouterId.of("4_as65001_c1");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_rr1.cfg",
                reflector("rr1", "10.0.0.1", "10.0.0.4"),
                reflector + ".cfg",
                reflector("rr2", "10.0.0.2", "10.0.0.3"),
                "3_as65001_c0.cfg",
                client("c0", "10.0.0.3", "10.0.0.2", null),
                client + ".cfg",
                client("c1", "10.0.0.4", "10.0.0.1", null)));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    BgpIncidenceSet incidence =
        BgpIncidenceSet.infer(program, List.of(client)).stream()
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
                repaired.text(client).text().contains("neighbor 10.0.0.2 peer-group RR")
                    && repaired.text(client).text().contains("neighbor 10.0.0.2 activate")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 peer-group IBGP")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 activate")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 next-hop-self"));
  }

  @Test
  void aWhollyMissingIncidenceInheritsAndMaterialisesTheSameAsGroupConvention() {
    RouterId reflector = RouterId.of("2_as65001_rr2");
    RouterId client = RouterId.of("4_as65001_c1");
    String clientWithoutIbgp =
        """
        hostname c1
        interface Loopback0
         ip address 10.0.0.4 255.255.255.255
        router bgp 65001
         bgp router-id 10.0.0.4
         address-family ipv4
         exit-address-family
        """;
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_rr1.cfg",
                reflector("rr1", "10.0.0.1", "10.0.0.3"),
                reflector + ".cfg",
                reflector("rr2", "10.0.0.2", "10.0.0.3"),
                "3_as65001_c0.cfg",
                client("c0", "10.0.0.3", "10.0.0.1", null),
                client + ".cfg",
                clientWithoutIbgp));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    BgpIncidenceSet incidence =
        BgpIncidenceSet.infer(program, List.of(client)).stream()
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
                repaired.text(client).text().contains("neighbor RR peer-group")
                    && repaired.text(client).text().contains("neighbor RR remote-as 65001")
                    && repaired.text(client).text().contains("neighbor RR update-source Loopback0")
                    && repaired.text(client).text().contains("neighbor 10.0.0.2 peer-group RR")
                    && repaired.text(client).text().contains("neighbor 10.0.0.2 activate")
                    && repaired
                        .text(reflector)
                        .text()
                        .contains("neighbor 10.0.0.4 peer-group IBGP"));
  }

  @Test
  void aVerifierRequiredProcessFormsOneDirectSameAsMeshTransaction() {
    RouterId third = RouterId.of("3_as65001_r3");
    String speakerOne =
        """
        hostname r1
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        router bgp 65001
         bgp router-id 10.0.0.1
         address-family ipv4
         exit-address-family
        """;
    String speakerTwo =
        speakerOne.replace("hostname r1", "hostname r2").replace("10.0.0.1", "10.0.0.2");
    String processless =
        """
        hostname r3
        interface Loopback0
         ip address 10.0.0.3 255.255.255.255
        """;
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg", speakerOne,
                TWO + ".cfg", speakerTwo,
                third + ".cfg", processless));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    ProgramModel program = programs.model(snapshot, topology);
    BgpMesh mesh = BgpMesh.infer(program, List.of(ONE, TWO, third), List.of(third)).getFirst();

    List<Hypothesis> hypotheses = new BgpMeshGenerator(seeds).generate(program, mesh);

    assertThat(hypotheses).hasSize(1);
    Snapshot repaired = snapshot.patch(hypotheses.getFirst().transaction()).after();
    assertThat(repaired.text(third).text())
        .contains("router bgp 65001")
        .contains("neighbor 10.0.0.1 remote-as 65001")
        .contains("neighbor 10.0.0.2 remote-as 65001")
        .contains("neighbor 10.0.0.1 activate")
        .contains("neighbor 10.0.0.2 activate");
    assertThat(repaired.text(ONE).text())
        .contains("neighbor 10.0.0.3 remote-as 65001")
        .contains("neighbor 10.0.0.3 update-source Loopback0")
        .contains("neighbor 10.0.0.3 activate");
    assertThat(repaired.text(TWO).text())
        .contains("neighbor 10.0.0.3 remote-as 65001")
        .contains("neighbor 10.0.0.3 activate");
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());
    RepairState meshState =
        RepairState.initial(
            new RepairProblem("mesh", snapshot, topology, SpecificationSet.of(List.of())),
            verification,
            program);
    assertThat(admissibility.admit(meshState, hypotheses)).containsExactlyElementsOf(hypotheses);
  }

  @Test
  void aVerifierRequiredProcessUsesAnObservedReflectorIncidenceWhenAvailable() {
    RouterId reflector = RouterId.of("2_as65001_rr2");
    RouterId client = RouterId.of("4_as65001_c1");
    String processless =
        """
        hostname c1
        interface Loopback0
         ip address 10.0.0.4 255.255.255.255
        """;
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                "1_as65001_rr1.cfg",
                reflector("rr1", "10.0.0.1", "10.0.0.3"),
                reflector + ".cfg",
                reflector("rr2", "10.0.0.2", "10.0.0.3"),
                "3_as65001_c0.cfg",
                client("c0", "10.0.0.3", "10.0.0.1", null),
                client + ".cfg",
                processless));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    BgpIncidenceSet incidence =
        BgpIncidenceSet.infer(program, List.of(client), List.of(client)).stream()
            .filter(item -> item.reflector().router().equals(reflector))
            .findFirst()
            .orElseThrow();

    List<Hypothesis> hypotheses = new BgpIncidenceGenerator(seeds).generate(program, incidence);

    assertThat(
            hypotheses.stream().map(hypothesis -> snapshot.patch(hypothesis.transaction()).after()))
        .anyMatch(
            repaired ->
                repaired.text(client).text().contains("router bgp 65001")
                    && repaired.text(client).text().contains("neighbor RR peer-group")
                    && repaired.text(client).text().contains("neighbor 10.0.0.2 peer-group RR")
                    && repaired.text(client).text().contains("neighbor 10.0.0.2 activate")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 peer-group IBGP")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 activate")
                    && repaired.text(reflector).text().contains("neighbor 10.0.0.4 next-hop-self"));
  }

  @Test
  void anOspfRouterMaterialisesItsMissingLoopbackOrigination() {
    String config =
        """
        hostname r1
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        router ospf 1
         router-id 10.0.0.1
         network 10.1.0.0 0.0.0.1 area 0
         network 10.0.0.1 0.0.0.0 area
        """;
    Snapshot snapshot = Snapshot.of(Map.of(ONE + ".cfg", config));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    RelationInstance missing =
        program.instances("ospf-loopback-origination").stream()
            .filter(instance -> !instance.satisfied())
            .findFirst()
            .orElseThrow();

    List<Hypothesis> hypotheses =
        closure.close(
            program,
            new Verification(
                snapshot.id(),
                new Verdict(List.of(), List.of(), List.of()),
                new DataPlaneEvidence(Map.of(), Map.of(), Map.of())),
            List.of(),
            Set.of(new ClosureEngine.Obligation(missing, missing.violated().getFirst())));

    assertThat(
            hypotheses.stream()
                .map(
                    hypothesis ->
                        snapshot.patch(hypothesis.transaction()).after().text(ONE).text()))
        .anyMatch(text -> text.contains("network 10.0.0.1 0.0.0.0 area 0"));

    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());
    RepairState repairState =
        RepairState.initial(
            new RepairProblem(
                "ospf-loopback", snapshot, program.topology(), SpecificationSet.of(List.of())),
            verification,
            program);
    assertThat(admissibility.admit(repairState, hypotheses)).isNotEmpty();
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
    Specification target =
        new Specification(predicate, Specification.Status.parse("broken_removed"));
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
    Specification unwanted =
        new Specification(predicate, Specification.Status.parse("broken_added"));
    assertThat(
            BgpSessionCut.infer(
                RepairState.initial(
                    problem,
                    new Verification(
                        snapshot.id(),
                        new Verdict(List.of(unwanted), List.of(), List.of()),
                        verification.evidence()),
                    cutting.program())))
        .isEmpty();

    List<Hypothesis> hypotheses = new BgpSessionCutGenerator().generate(cutting.program(), cut);

    assertThat(hypotheses).hasSize(1);
    Snapshot repaired = snapshot.patch(hypotheses.getFirst().transaction()).after();
    assertThat(repaired.text(ONE).text()).doesNotContain("neighbor 10.0.0.2");
    assertThat(repaired.text(TWO).text()).doesNotContain("neighbor 10.0.0.1");
    assertThat(repaired.text(ONE).text()).contains("neighbor IBGP peer-group");
    assertThat(admissibility.admit(cutting, hypotheses)).containsExactlyElementsOf(hypotheses);
  }

  @Test
  void anAcceptedWaypointBypassCutsOnlyTheAlternateReciprocalSession() {
    RouterId waypoint = RouterId.of("2_as65001_waypoint");
    RouterId bypass = RouterId.of("3_as65001_bypass");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg", reciprocalSpeaker("source", "10.0.0.1", "10.0.0.2", "10.0.0.3"),
                waypoint + ".cfg", reciprocalSpeaker("waypoint", "10.0.0.2", "10.0.0.1"),
                bypass + ".cfg", reciprocalSpeaker("bypass", "10.0.0.3", "10.0.0.1")));
    ForwardingPredicate predicate =
        new ForwardingPredicate.Waypointing(ONE, Prefix.parse("10.200.0.0/24"), waypoint);
    Specification target =
        new Specification(predicate, Specification.Status.parse("broken_removed"));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    RepairProblem problem =
        new RepairProblem("waypoint-cut", snapshot, topology, SpecificationSet.of(List.of(target)));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(target), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    predicate,
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(ONE, bypass), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState cutting =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));

    List<BgpSessionCut> cuts = BgpSessionCut.infer(cutting);

    assertThat(cuts).hasSize(1);
    assertThat(cuts.getFirst().session().routers()).containsExactlyInAnyOrder(ONE, bypass);
    Snapshot repaired =
        snapshot
            .patch(
                new BgpSessionCutGenerator()
                    .generate(cutting.program(), cuts.getFirst())
                    .getFirst()
                    .transaction())
            .after();
    assertThat(repaired.text(ONE).text())
        .contains("neighbor 10.0.0.2")
        .doesNotContain("neighbor 10.0.0.3");
    assertThat(repaired.text(bypass).text()).doesNotContain("neighbor 10.0.0.1");
  }

  private static String reciprocalSpeaker(String hostname, String address, String... peers) {
    StringBuilder config =
        new StringBuilder(
            "hostname "
                + hostname
                + "\ninterface Loopback0\n ip address "
                + address
                + " 255.255.255.255\nrouter bgp 65001\n bgp router-id "
                + address
                + "\n");
    for (String peer : peers) {
      config.append(" neighbor ").append(peer).append(" remote-as 65001\n");
    }
    config.append(" address-family ipv4\n");
    for (String peer : peers) {
      config.append("  neighbor ").append(peer).append(" activate\n");
    }
    return config.append(" exit-address-family\n").toString();
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
                R1.replace(
                        """
                          neighbor 10.0.0.2 activate
                        """,
                        """
                          neighbor 10.0.0.2 activate
                          neighbor 10.0.0.2 route-map IMPORT in
                        """)
                    + """

                    route-map IMPORT permit 10
                    route-map RM_BGP_TO_OSPF permit 10
                    """,
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
        .anyMatch(
            text ->
                text.contains("neighbor 10.0.0.2 route-map IMPORT in")
                    && text.contains("neighbor 10.0.0.2 route-map RM_BGP_TO_OSPF out"));
    assertThat(
            hypotheses.stream()
                .map(h -> unused.snapshot().patch(h.transaction()).after().text(ONE).text()))
        .noneMatch(text -> text.contains("neighbor 10.0.0.2 route-map RM_BGP_TO_OSPF in"));
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
        new ProtocolTransferGenerator(closure, seeds)
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
  void aForbiddenAcceptedPathOffersTheExactPolicyAttachment() {
    String source =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        router bgp 65001
         neighbor 10.1.0.0 remote-as 65002
         address-family ipv4
          neighbor 10.1.0.0 activate
         exit-address-family
        ip prefix-list BLOCK seq 10 permit 10.200.0.0/24
        route-map IMPORT deny 10
         match ip address prefix-list BLOCK
        route-map IMPORT permit 20
        """;
    String firstHop =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        router bgp 65002
         neighbor 10.1.0.1 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.1 activate
         exit-address-family
        """;
    Snapshot snapshot = Snapshot.of(Map.of(ONE + ".cfg", source, TWO + ".cfg", firstHop));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(ONE, "GigabitEthernet0/0"),
                    new Topology.Endpoint(TWO, "GigabitEthernet0/0"))));
    Specification forbidden =
        new Specification(
            new ForwardingPredicate.Reachability(ONE, Prefix.parse("10.200.0.0/24")),
            Specification.Status.parse("broken_added"));
    RepairProblem problem =
        new RepairProblem("forbidden", snapshot, topology, SpecificationSet.of(List.of(forbidden)));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(forbidden), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    forbidden.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(ONE, TWO), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState forbiddenState =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));

    ProtocolTransfer transfer =
        ProtocolTransfer.infer(forbiddenState).stream()
            .filter(candidate -> candidate.attachment().isPresent())
            .findFirst()
            .orElseThrow();
    List<Hypothesis> hypotheses =
        new ProtocolTransferGenerator(closure, seeds)
            .generate(forbiddenState.program(), forbiddenState.verification(), transfer);

    assertThat(transfer.policy().key()).containsExactly("IMPORT");
    assertThat(transfer.attachment().orElseThrow().direction()).isEqualTo("in");
    assertThat(hypotheses).hasSize(1);
    assertThat(
            forbiddenState
                .snapshot()
                .patch(hypotheses.getFirst().transaction())
                .after()
                .text(ONE)
                .text())
        .contains("neighbor 10.1.0.0 route-map IMPORT in");

    Specification forbiddenWaypoint =
        new Specification(
            new ForwardingPredicate.Waypointing(ONE, Prefix.parse("10.200.0.0/24"), TWO),
            Specification.Status.parse("broken_added"));
    RepairProblem waypointProblem =
        new RepairProblem(
            "forbidden-waypoint",
            snapshot,
            topology,
            SpecificationSet.of(List.of(forbiddenWaypoint)));
    Verification waypointVerification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(forbiddenWaypoint), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    forbiddenWaypoint.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(ONE, TWO), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState forbiddenWaypointState =
        RepairState.initial(
            waypointProblem, waypointVerification, programs.model(snapshot, topology));

    assertThat(ProtocolTransfer.infer(forbiddenWaypointState))
        .anyMatch(candidate -> candidate.attachment().isPresent());
  }

  @Test
  void aForbiddenAcceptedPathOffersAnAttachmentWhereItCrossesAutonomousSystems() {
    RouterId exporter = RouterId.of("3_as65002_r3");
    String source =
        """
        hostname r1
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        router bgp 65001
         neighbor 10.0.0.2 remote-as 65001
         address-family ipv4
          neighbor 10.0.0.2 activate
         exit-address-family
        """;
    String border =
        """
        hostname r2
        interface Loopback0
         ip address 10.0.0.2 255.255.255.255
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        router bgp 65001
         neighbor 10.0.0.1 remote-as 65001
         neighbor 10.1.0.0 remote-as 65002
         address-family ipv4
          neighbor 10.0.0.1 activate
          neighbor 10.1.0.0 activate
         exit-address-family
        """;
    String origin =
        """
        hostname r3
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        router bgp 65002
         neighbor 10.1.0.1 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.1 activate
         exit-address-family
        ip prefix-list BLOCK seq 10 permit 10.200.0.0/24
        route-map EXPORT deny 10
         match ip address prefix-list BLOCK
        route-map EXPORT permit 20
        """;
    Snapshot snapshot =
        Snapshot.of(Map.of(ONE + ".cfg", source, TWO + ".cfg", border, exporter + ".cfg", origin));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(TWO, "GigabitEthernet0/0"),
                    new Topology.Endpoint(exporter, "GigabitEthernet0/0"))));
    Specification forbidden =
        new Specification(
            new ForwardingPredicate.Reachability(ONE, Prefix.parse("10.200.0.0/24")),
            Specification.Status.parse("broken_added"));
    RepairProblem problem =
        new RepairProblem("leak", snapshot, topology, SpecificationSet.of(List.of(forbidden)));
    Verification verification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(forbidden), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    forbidden.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(ONE, TWO, exporter), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState leaking =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));

    assertThat(ProtocolTransfer.infer(leaking))
        .filteredOn(transfer -> transfer.attachment().isPresent())
        .singleElement()
        .satisfies(
            transfer -> {
              assertThat(transfer.policy())
                  .isEqualTo(new ProgramObject.Ref("route-map", exporter, List.of("EXPORT")));
              assertThat(transfer.attachment().orElseThrow())
                  .isEqualTo(
                      new ProtocolTransfer.Attachment(
                          new ProgramObject.Ref("bgp-neighbor", exporter, List.of("10.1.0.1")),
                          "out"));
            });
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
  void anInterAreaIsisObligationCanRelaxALevelOneOnlyRouter() {
    String first =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
         ip router isis 1
        router isis 1
         net 49.0001.0000.0000.0001.00
         is-type level-1
        """;
    String second =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
         ip router isis 1
        router isis 1
         net 49.0002.0000.0000.0002.00
         is-type level-1-2
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", first, "2_as65001_r2.cfg", second));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(ONE, "GigabitEthernet0/0"),
                    new Topology.Endpoint(TWO, "GigabitEthernet0/0"))));
    RepairProblem problem =
        new RepairProblem("isis-level", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState mismatched =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    RelationInstance link = mismatched.program().instances("link").getFirst();
    Requirement area =
        link.violated().stream()
            .filter(requirement -> requirement.text().contains("router.isis-area"))
            .findFirst()
            .orElseThrow();

    List<Hypothesis> hypotheses =
        closure.close(
            mismatched.program(),
            mismatched.verification(),
            List.of(),
            Set.of(new ClosureEngine.Obligation(link, area)));

    assertThat(hypotheses)
        .anyMatch(
            hypothesis ->
                mismatched
                    .snapshot()
                    .patch(hypothesis.transaction())
                    .after()
                    .text(ONE)
                    .text()
                    .contains(" is-type level-1-2"));
  }

  @Test
  void aPolicyAttachedAgainstItsDirectionBreaksASoftRequirement() {
    java.util.function.BiFunction<String, String, Snapshot> attached =
        (inbound, outbound) ->
            Snapshot.of(
                Map.of(
                    ONE + ".cfg",
                    """
                    hostname r1
                    interface GigabitEthernet0/0
                     ip address 10.1.0.0 255.255.255.254
                    route-map IMPORT permit 10
                     set local-preference 100
                    route-map EXPORT permit 10
                     set metric 20
                     set as-path prepend 65001
                    router bgp 65001
                     neighbor 10.1.0.1 remote-as 65002
                     address-family ipv4
                      neighbor 10.1.0.1 activate
                      neighbor 10.1.0.1 route-map %s in
                      %s
                     exit-address-family
                    """
                        .formatted(
                            inbound,
                            outbound.isEmpty()
                                ? "!"
                                : "neighbor 10.1.0.1 route-map " + outbound + " out"),
                    TWO + ".cfg",
                    """
                    hostname r2
                    interface GigabitEthernet0/0
                     ip address 10.1.0.1 255.255.255.254
                    router bgp 65002
                     neighbor 10.1.0.0 remote-as 65001
                    """));
    java.util.function.Function<Snapshot, RelationInstance> policy =
        snapshot ->
            programs
                .model(snapshot, Topology.of(snapshot.routers(), List.of()))
                .instances("bgp-policy")
                .stream()
                .filter(instance -> instance.routers().contains(ONE))
                .findFirst()
                .orElseThrow();

    assertThat(policy.apply(attached.apply("IMPORT", "EXPORT")).violated()).isEmpty();
    assertThat(policy.apply(attached.apply("EXPORT", "IMPORT")).violated())
        .extracting(Requirement::text)
        .containsExactly(
            "soft absent neighbor.route-map-in.export-attributes",
            "soft absent neighbor.route-map-out.import-attributes");

    Snapshot misdirected = attached.apply("EXPORT", "");
    ProgramModel program =
        programs.model(misdirected, Topology.of(misdirected.routers(), List.of()));
    List<Hypothesis> repairs =
        new PolicyDirectionRepair(seeds)
            .generate(program, policy.apply(misdirected).roles().get("neighbor"));
    assertThat(repairs)
        .extracting(repair -> misdirected.patch(repair.transaction()).after())
        .extracting(
            repaired ->
                repaired
                    .text(ONE)
                    .text()
                    .lines()
                    .filter(line -> line.contains("route-map") && line.contains("neighbor"))
                    .map(String::strip)
                    .toList())
        .containsExactly(
            List.of(
                "neighbor 10.1.0.1 route-map IMPORT in", "neighbor 10.1.0.1 route-map EXPORT out"),
            List.of("neighbor 10.1.0.1 route-map EXPORT out"));
    assertThat(repairs)
        .allSatisfy(
            repair ->
                assertThat(policy.apply(misdirected.patch(repair.transaction()).after()).violated())
                    .isEmpty());
  }

  @Test
  void aLevelOneAreaWithoutAWayOutIsRepairedByRaisingOneOfItsRouters() {
    java.util.function.BiFunction<Integer, String, String> router =
        (n, level) ->
            """
            hostname r%d
            interface GigabitEthernet0/0
             ip address 10.1.0.%d 255.255.255.254
             ip router isis 1
            router isis 1
             net 49.0001.0000.0000.000%d.00
             is-type %s
            """
                .formatted(n, n, n, level);
    Topology.Link link =
        new Topology.Link(
            new Topology.Endpoint(ONE, "GigabitEthernet0/0"),
            new Topology.Endpoint(TWO, "GigabitEthernet0/0"));
    Snapshot attached =
        Snapshot.of(
            Map.of(
                ONE + ".cfg", router.apply(0, "level-1"),
                TWO + ".cfg", router.apply(1, "level-1-2")));
    assertThat(
            programs
                .model(attached, Topology.of(attached.routers(), List.of(link)))
                .instances("isis-attachment"))
        .hasSize(2)
        .allMatch(RelationInstance::satisfied);

    Snapshot confined =
        Snapshot.of(
            Map.of(
                ONE + ".cfg", router.apply(0, "level-1"),
                TWO + ".cfg", router.apply(1, "level-1")));
    ProgramModel program = programs.model(confined, Topology.of(confined.routers(), List.of(link)));
    assertThat(program.instances("isis-attachment")).noneMatch(RelationInstance::satisfied);
    RelationInstance attachment =
        program.instances("isis-attachment").stream()
            .filter(instance -> instance.routers().contains(TWO))
            .findFirst()
            .orElseThrow();
    Verification verification =
        new Verification(confined.id(), state.verification().verdict(), DataPlaneEvidence.none());

    List<Hypothesis> hypotheses =
        closure.close(
            program,
            verification,
            List.of(),
            Set.of(new ClosureEngine.Obligation(attachment, attachment.violated().getFirst())));

    assertThat(hypotheses)
        .extracting(
            hypothesis ->
                hypothesis.routers().stream()
                    .filter(
                        changed ->
                            confined
                                .patch(hypothesis.transaction())
                                .after()
                                .text(changed)
                                .text()
                                .contains(" is-type level-1-2"))
                    .toList())
        .as("the router itself, then the other level-1 router of the area")
        .startsWith(List.of(TWO), List.of(ONE));
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
  void aLatentIgpAdjacencyActivatesBothPhysicalEndpointsAtomically() {
    RouterId firstId = RouterId.of("1_as65001_r1");
    RouterId secondId = RouterId.of("2_as65002_r2");
    String first =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.2.0.1 255.255.255.254
         ip ospf network point-to-point
         ip ospf 1 area 0
         ip ospf cost 10
        router ospf 1
        """;
    String second =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.3.0.1 255.255.255.254
         ip ospf network point-to-point
         ip ospf 1 area 0
         ip ospf cost 10
        router ospf 1
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", first, "2_as65002_r2.cfg", second));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(firstId, "GigabitEthernet0/0"),
                    new Topology.Endpoint(secondId, "GigabitEthernet0/0"))));
    RepairProblem problem =
        new RepairProblem("latent-igp", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState latent =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    RelationInstance link = latent.program().instances("link").getFirst();

    List<Hypothesis> hypotheses =
        new IgpAdjacencyGenerator(seeds, closure)
            .generate(latent.program(), latent.verification(), link);

    assertThat(
            hypotheses.stream()
                .map(hypothesis -> latent.snapshot().patch(hypothesis.transaction()).after()))
        .anyMatch(
            repaired ->
                repaired.text(firstId).text().contains(" ip ospf 1 area 0")
                    && repaired.text(firstId).text().contains(" ip ospf cost 10")
                    && repaired.text(firstId).text().contains(" ip ospf network point-to-point")
                    && repaired.text(secondId).text().contains(" ip ospf 1 area 0")
                    && repaired.text(secondId).text().contains(" ip ospf cost 10")
                    && repaired.text(secondId).text().contains(" ip ospf network point-to-point"));
  }

  @Test
  void narrowingAnInterfaceSubnetNarrowsTheOspfNetworkStatementThatNamesIt() {
    RouterId firstId = RouterId.of("1_as65001_r1");
    RouterId secondId = RouterId.of("2_as65001_r2");
    String first =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.252
        interface GigabitEthernet0/1
         ip address 10.1.0.2 255.255.255.254
        router ospf 1
         network 10.1.0.0 0.0.0.3 area 0
        """;
    String second =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        router ospf 1
         network 10.1.0.0 0.0.0.1 area 0
        """;
    Snapshot snapshot = Snapshot.of(Map.of(firstId + ".cfg", first, secondId + ".cfg", second));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(firstId, "GigabitEthernet0/0"),
                    new Topology.Endpoint(secondId, "GigabitEthernet0/0"))));
    RepairProblem problem =
        new RepairProblem("wide-subnet", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState wide =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    RelationInstance link =
        wide.program().instances("link").stream()
            .filter(instance -> !instance.satisfied())
            .findFirst()
            .orElseThrow();
    Set<ClosureEngine.Obligation> obligations =
        link.violated().stream()
            .filter(requirement -> requirement.text().contains("equal a.subnet b.subnet"))
            .map(requirement -> new ClosureEngine.Obligation(link, requirement))
            .collect(java.util.stream.Collectors.toSet());

    List<String> repaired =
        closure.close(wide.program(), wide.verification(), List.of(), obligations).stream()
            .map(hypothesis -> wide.snapshot().patch(hypothesis.transaction()).after())
            .map(after -> after.text(firstId).text())
            .toList();

    assertThat(repaired)
        .anyMatch(
            text ->
                text.contains(" ip address 10.1.0.0 255.255.255.254")
                    && text.contains(" network 10.1.0.0 0.0.0.1 area 0"));
    assertThat(repaired)
        .noneMatch(
            text ->
                text.contains(" ip address 10.1.0.0 255.255.255.254")
                    && text.contains(" network 10.1.0.0 0.0.0.3 area 0"));
  }

  /**
   * Each end already has one interface with its own cost, and the activated link adds a second: the
   * counts below are of each cost on each router.
   */
  @Test
  void anActivatedLinkFirstAgreesOnACostAndOffersTheCostsBothEndsKnow() {
    RouterId firstId = RouterId.of("1_as65001_r1");
    RouterId secondId = RouterId.of("2_as65001_r2");
    String first =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.2.0.1 255.255.255.254
         ip ospf network point-to-point
         ip ospf 1 area 0
         ip ospf cost 20
        router ospf 1
        """;
    String second =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.3.0.1 255.255.255.254
         ip ospf network point-to-point
         ip ospf 1 area 0
         ip ospf cost 30
        router ospf 1
        """;
    Snapshot snapshot = Snapshot.of(Map.of(firstId + ".cfg", first, secondId + ".cfg", second));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(firstId, "GigabitEthernet0/0"),
                    new Topology.Endpoint(secondId, "GigabitEthernet0/0"))));
    RepairProblem problem =
        new RepairProblem("link-costs", snapshot, topology, state.problem().specifications());
    Verification verification =
        new Verification(snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none());
    RepairState latent =
        RepairState.initial(problem, verification, programs.model(snapshot, topology));
    RelationInstance link = latent.program().instances("link").getFirst();

    List<List<Integer>> costs =
        new IgpAdjacencyGenerator(seeds, closure)
            .generate(latent.program(), latent.verification(), link).stream()
                .map(hypothesis -> latent.snapshot().patch(hypothesis.transaction()).after())
                .map(
                    repaired ->
                        List.of(
                            occurrences(repaired.text(firstId).text(), " ip ospf cost 20"),
                            occurrences(repaired.text(secondId).text(), " ip ospf cost 20"),
                            occurrences(repaired.text(firstId).text(), " ip ospf cost 30"),
                            occurrences(repaired.text(secondId).text(), " ip ospf cost 30")))
                .toList();

    List<Integer> bothTwenty = List.of(2, 1, 0, 1);
    List<Integer> bothThirty = List.of(1, 0, 1, 2);
    List<Integer> oneEach = List.of(2, 0, 0, 2);
    assertThat(costs).contains(bothTwenty, bothThirty, oneEach);
    assertThat(costs.getFirst()).isEqualTo(bothTwenty);

    List<Boolean> literal =
        new IgpAdjacencyGenerator(seeds, closure)
            .generate(latent.program(), latent.verification(), link).stream()
                .map(hypothesis -> latent.snapshot().patch(hypothesis.transaction()).after())
                .map(
                    repaired ->
                        !(repaired.text(firstId).text() + repaired.text(secondId).text())
                            .replace("ip ospf network point-to-point", "")
                            .contains("ip ospf network"))
                .toList();
    assertThat(literal)
        .as("a network type no interface has comes after every profile the network holds")
        .isSortedAccordingTo(Comparator.reverseOrder());
  }

  @Test
  void activatingAnOspfLinkOffersTheCompleteObservedLinkProfile() {
    String first =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.2.0.1 255.255.255.254
         ip ospf 1 area 0
         ip ospf cost 10
         ip ospf network point-to-point
        router ospf 1
        """;
    String second =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.3.0.1 255.255.255.254
         ip ospf 1 area 0
         ip ospf cost 10
         ip ospf network point-to-point
        router ospf 1
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", first, "2_as65001_r2.cfg", second));
    Topology topology = state.problem().topology();
    RepairProblem problem =
        new RepairProblem("ospf-link", snapshot, topology, state.problem().specifications());
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

    List<Snapshot> repaired =
        hypotheses.stream()
            .map(hypothesis -> missing.snapshot().patch(hypothesis.transaction()).after())
            .toList();
    assertThat(repaired)
        .anyMatch(
            candidate ->
                occurrences(candidate.text(ONE).text(), " ip ospf 1 area 0") == 2
                    && occurrences(candidate.text(TWO).text(), " ip ospf 1 area 0") == 2
                    && occurrences(candidate.text(ONE).text(), " ip ospf cost 10") == 2
                    && occurrences(candidate.text(TWO).text(), " ip ospf cost 10") == 2
                    && occurrences(candidate.text(ONE).text(), " ip ospf network point-to-point")
                        == 2);
    assertThat(repaired)
        .allMatch(
            candidate ->
                occurrences(candidate.text(ONE).text(), " ip ospf 1 area 0")
                        == occurrences(candidate.text(ONE).text(), " ip ospf cost ")
                    && occurrences(candidate.text(ONE).text(), " ip ospf 1 area 0")
                        == occurrences(candidate.text(ONE).text(), " ip ospf network ")
                    && occurrences(candidate.text(TWO).text(), " ip ospf 1 area 0")
                        == occurrences(candidate.text(TWO).text(), " ip ospf cost ")
                    && occurrences(candidate.text(TWO).text(), " ip ospf 1 area 0")
                        == occurrences(candidate.text(TWO).text(), " ip ospf network "));
  }

  private static int occurrences(String text, String needle) {
    return (text.length() - text.replace(needle, "").length()) / needle.length();
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
         neighbor RR peer-group
         neighbor RR remote-as 65001
         neighbor IBGP peer-group
         neighbor IBGP remote-as 65001
         neighbor 10.0.0.1 peer-group RR
         neighbor 10.0.0.1 peer-group IBGP
         address-family ipv4
          neighbor 10.0.0.1 activate
          neighbor 10.0.0.1 activate
          neighbor 10.0.0.1 next-hop-self
        exit-address-family
        """;
    String fourth =
        """
        hostname r4
        interface Loopback0
         ip address 10.0.0.4 255.255.255.255
        router bgp 65001
         bgp router-id 10.0.0.4
         neighbor IBGP peer-group
         neighbor IBGP remote-as 65001
         neighbor 10.0.0.1 peer-group IBGP
         address-family ipv4
          neighbor 10.0.0.1 activate
         exit-address-family
        """;
    RouterId three = RouterId.of("3_as65001_r3");
    RouterId four = RouterId.of("4_as65001_r4");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg",
                first,
                TWO + ".cfg",
                second,
                three + ".cfg",
                third,
                four + ".cfg",
                fourth));
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
                        == 3
                    && repaired.text(three).text().contains("neighbor 10.0.0.2 next-hop-self")
                    && repaired.text(three).text().contains("neighbor 10.0.0.1 peer-group RR")
                    && repaired
                            .text(three)
                            .text()
                            .lines()
                            .filter(line -> line.contains("neighbor 10.0.0.1"))
                            .count()
                        == 2);

    assertThat(
            refactored.stream().map(hypothesis -> snapshot.patch(hypothesis.transaction()).after()))
        .anyMatch(
            repaired ->
                repaired.text(ONE).text().contains("ip address 10.0.0.2 255.255.255.255")
                    && repaired.text(TWO).text().contains("neighbor 10.0.0.1 peer-group IBGP")
                    && repaired
                            .text(three)
                            .text()
                            .lines()
                            .filter(line -> line.contains("neighbor 10.0.0.2"))
                            .count()
                        == 3);

    assertThat(
            refactored.stream().map(hypothesis -> snapshot.patch(hypothesis.transaction()).after()))
        .anyMatch(
            repaired ->
                repaired.text(four).text().contains("neighbor 10.0.0.2 peer-group IBGP")
                    && repaired.text(four).text().contains("neighbor 10.0.0.2 activate")
                    && repaired.text(TWO).text().contains("neighbor 10.0.0.1 peer-group IBGP"));

    Specification protectedHost =
        new Specification(
            new ForwardingPredicate.Waypointing(TWO, Prefix.parse("10.0.0.1/32"), ONE),
            Specification.Status.parse("broken_removed"));
    Verification protectedVerification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(protectedHost), List.of(), List.of()),
            DataPlaneEvidence.none());
    assertThat(IdentityRefactoring.complete(colliding.program(), protectedVerification, base))
        .isEmpty();

    Specification missingHost =
        new Specification(
            new ForwardingPredicate.Waypointing(TWO, Prefix.parse("10.0.0.2/32"), ONE),
            Specification.Status.parse("broken_removed"));
    Verification ownershipVerification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(protectedHost, missingHost), List.of(), List.of()),
            DataPlaneEvidence.none());
    ProgramObject.Ref firstLoopback = new ProgramObject.Ref("interface", ONE, List.of("Loopback0"));
    List<List<EditOperator>> ownershipSeeds =
        IdentityRefactoring.verificationSeeds(
            colliding.program(), ownershipVerification, firstLoopback);
    assertThat(ownershipSeeds).hasSize(1);
    Hypothesis ownership = Hypothesis.of(snapshot, ownershipSeeds.getFirst());
    assertThat(snapshot.patch(ownership.transaction()).after().text(ONE).text())
        .contains("ip address 10.0.0.2 255.255.255.255");
    assertThat(
            IdentityRefactoring.complete(
                colliding.program(), ownershipVerification, List.of(ownership)))
        .isNotEmpty()
        .allSatisfy(
            hypothesis ->
                assertThat(snapshot.patch(hypothesis.transaction()).after().text(ONE).text())
                    .contains("ip address 10.0.0.2 255.255.255.255"));

    RepairState ownershipState =
        RepairState.initial(problem, ownershipVerification, colliding.program());
    SearchSpaceBuilder ownershipSpaces =
        new SearchSpaceBuilder(
            seeds,
            closure,
            admissibility,
            new SearchSpace.Capacity(16, 256),
            Bounds.defaults(),
            context);
    RelationRegion ownershipRegion =
        ownershipSpaces.build(ownershipState).regions().stream()
            .filter(region -> region instanceof RelationRegion)
            .map(region -> (RelationRegion) region)
            .filter(region -> region.instance().relation().equals("address-identity"))
            .filter(region -> region.instance().routers().contains(ONE))
            .findFirst()
            .orElseThrow();
    Region expandedOwnership = ownershipSpaces.expand(ownershipRegion, ownershipState);
    assertThat(((Region.Hypotheses.Admitted) expandedOwnership.hypotheses()).list())
        .anyMatch(
            hypothesis ->
                snapshot
                    .patch(hypothesis.transaction())
                    .after()
                    .text(ONE)
                    .text()
                    .contains("ip address 10.0.0.2 255.255.255.255"));

    Specification transit =
        new Specification(
            new ForwardingPredicate.Waypointing(three, Prefix.parse("10.0.0.2/32"), ONE),
            Specification.Status.parse("broken_removed"));
    Specification beyond =
        new Specification(
            new ForwardingPredicate.Waypointing(ONE, Prefix.parse("10.0.0.2/32"), TWO),
            Specification.Status.parse("broken_removed"));
    Verification transitVerification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(protectedHost, transit, beyond), List.of(), List.of()),
            DataPlaneEvidence.none());
    assertThat(
            IdentityRefactoring.verificationSeeds(
                colliding.program(), transitVerification, firstLoopback))
        .isEmpty();
    assertThat(IdentityRefactoring.complete(colliding.program(), transitVerification, base))
        .isEmpty();
    List<List<EditOperator>> beyondSeeds =
        IdentityRefactoring.verificationSeeds(
            colliding.program(),
            transitVerification,
            new ProgramObject.Ref("interface", TWO, List.of("Loopback0")));
    assertThat(beyondSeeds).hasSize(1);
    assertThat(
            snapshot
                .patch(Hypothesis.of(snapshot, beyondSeeds.getFirst()).transaction())
                .after()
                .text(TWO)
                .text())
        .contains("ip address 10.0.0.2 255.255.255.255");

    Specification secondMissingHost =
        new Specification(
            new ForwardingPredicate.Waypointing(ONE, Prefix.parse("10.0.0.5/32"), TWO),
            Specification.Status.parse("broken_removed"));
    Verification secondOwnershipVerification =
        new Verification(
            snapshot.id(),
            new Verdict(List.of(protectedHost, secondMissingHost), List.of(), List.of()),
            DataPlaneEvidence.none());
    RepairState secondOwnershipState =
        RepairState.initial(problem, secondOwnershipVerification, colliding.program());
    ProgramObject.Ref secondLoopback =
        new ProgramObject.Ref("interface", TWO, List.of("Loopback0"));
    List<List<EditOperator>> secondOwnershipSeeds =
        IdentityRefactoring.verificationSeeds(
            colliding.program(), secondOwnershipVerification, secondLoopback);
    assertThat(secondOwnershipSeeds).hasSize(1);
    Hypothesis secondOwnership = Hypothesis.of(snapshot, secondOwnershipSeeds.getFirst());
    assertThat(
            IdentityRefactoring.complete(
                colliding.program(), secondOwnershipVerification, List.of(secondOwnership)))
        .anyMatch(
            hypothesis ->
                snapshot
                    .patch(hypothesis.transaction())
                    .after()
                    .text(TWO)
                    .text()
                    .contains("ip address 10.0.0.5 255.255.255.255"));
    RelationRegion symmetricCollision =
        ownershipSpaces.build(secondOwnershipState).regions().stream()
            .filter(region -> region instanceof RelationRegion)
            .map(region -> (RelationRegion) region)
            .filter(region -> region.instance().relation().equals("address-identity"))
            .findFirst()
            .orElseThrow();
    Region expandedSymmetricCollision =
        ownershipSpaces.expand(symmetricCollision, secondOwnershipState);
    assertThat(((Region.Hypotheses.Admitted) expandedSymmetricCollision.hypotheses()).list())
        .anyMatch(
            hypothesis ->
                snapshot
                    .patch(hypothesis.transaction())
                    .after()
                    .text(TWO)
                    .text()
                    .contains("ip address 10.0.0.5 255.255.255.255"));

    Bounds bounds = Bounds.defaults();
    Snapshot missingSnapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg",
                first
                    .replace("router-id 10.0.0.2", "router-id 10.0.0.1")
                    .replace("bgp router-id 10.0.0.2", "bgp router-id 10.0.0.1"),
                TWO + ".cfg",
                second,
                three + ".cfg",
                third));
    RepairProblem missingProblem =
        new RepairProblem(
            "missing-identity", missingSnapshot, topology, state.problem().specifications());
    RepairState missing =
        RepairState.initial(
            missingProblem,
            new Verification(
                missingSnapshot.id(), state.verification().verdict(), DataPlaneEvidence.none()),
            programs.model(missingSnapshot, topology));
    SearchSpaceBuilder spaces =
        new SearchSpaceBuilder(
            seeds, closure, admissibility, new SearchSpace.Capacity(16, 256), bounds, context);
    RelationRegion addressCollision =
        spaces.build(missing).regions().stream()
            .filter(region -> region instanceof RelationRegion)
            .map(region -> (RelationRegion) region)
            .filter(region -> region.instance().relation().equals("address-identity"))
            .filter(region -> region.instance().routers().contains(ONE))
            .findFirst()
            .orElseThrow();
    Region expanded = spaces.expand(addressCollision, missing);
    assertThat(((Region.Hypotheses.Admitted) expanded.hypotheses()).list())
        .anyMatch(
            hypothesis ->
                missingSnapshot
                    .patch(hypothesis.transaction())
                    .after()
                    .text(ONE)
                    .text()
                    .contains("ip address 10.0.0.2 255.255.255.255"));
  }

  @Test
  void aCollidingLoopbackUsesAnExactLeaveOneOutIsisIdentityRelation() {
    String target =
        """
        hostname target
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        router isis 1
         net 31.e900.0000.0ab4.0002.00
        """;
    String first =
        """
        hostname first
        interface Loopback0
         ip address 10.0.0.1 255.255.255.255
        router isis 1
         net 31.e900.0000.0ab4.0001.00
        """;
    String third =
        """
        hostname third
        interface Loopback0
         ip address 10.0.0.3 255.255.255.255
        router isis 1
         net 31.e900.0000.0ab4.0003.00
        router bgp 65001
         neighbor IBGP peer-group
         neighbor IBGP remote-as 65001
         neighbor 10.0.0.1 peer-group IBGP
         address-family ipv4
          neighbor 10.0.0.1 activate
         exit-address-family
        """;
    RouterId otherAsOwner = RouterId.of("2_as65002_other");
    RouterId thirdRouter = RouterId.of("3_as65001_r3");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg", target, otherAsOwner + ".cfg", first, thirdRouter + ".cfg", third));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref loopback = new ProgramObject.Ref("interface", ONE, List.of("Loopback0"));

    List<List<EditOperator>> candidates = IdentityRefactoring.consensusSeeds(program, loopback);

    assertThat(candidates).hasSize(1);
    Hypothesis candidate = Hypothesis.of(snapshot, candidates.getFirst());
    assertThat(snapshot.patch(candidate.transaction()).after().text(ONE).text())
        .contains("ip address 10.0.0.2 255.255.255.255");
    Hypothesis completed = IdentityRefactoring.complete(program, List.of(candidate)).getFirst();
    Snapshot repaired = snapshot.patch(completed.transaction()).after();
    assertThat(repaired.text(thirdRouter).text())
        .contains("neighbor 10.0.0.2 peer-group IBGP")
        .contains("neighbor 10.0.0.2 activate");
  }

  @Test
  void allLeadingUnconditionalAclBlockersFormOneAtomicRepair() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg",
                """
                hostname r1
                ip access-list extended EDGE_OUT
                 5 deny ip any any
                 6 deny ip any any
                 10 permit ip host 10.0.0.1 any
                 20 deny ip any any
                """));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref acl = new ProgramObject.Ref("acl", ONE, List.of("EDGE_OUT"));

    List<Hypothesis> hypotheses = FirstMatchRepair.generate(program, acl);

    assertThat(hypotheses).hasSize(1);
    assertThat(hypotheses.getFirst().support()).isEqualTo(ValueSupport.EVIDENCED);
    String repaired = snapshot.patch(hypotheses.getFirst().transaction()).after().text(ONE).text();
    assertThat(repaired)
        .doesNotContain("5 deny ip any any")
        .doesNotContain("6 deny ip any any")
        .contains("10 permit ip host 10.0.0.1 any")
        .contains("20 deny ip any any");
  }

  @Test
  void aDenyClauseWithAnUnreachableTransformationIsOpened() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg",
                """
                hostname r1
                ip prefix-list EXPORT seq 10 permit 10.200.0.0/24
                route-map OUT deny 100
                 match ip address prefix-list EXPORT
                 set community no-advertise additive
                """));
    ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
    ProgramObject.Ref entry = new ProgramObject.Ref("route-map-entry", ONE, List.of("OUT", "100"));

    List<Hypothesis> hypotheses = FirstMatchRepair.generate(program, entry);

    assertThat(hypotheses).hasSize(1);
    String repaired = snapshot.patch(hypotheses.getFirst().transaction()).after().text(ONE).text();
    assertThat(repaired)
        .contains("route-map OUT permit 100")
        .contains("set community no-advertise additive");
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
  void aJointlyAgreedOutlierIsRevisedOnBothEndsAtomically() {
    RouterId a = RouterId.of("10_as65001_a");
    RouterId b = RouterId.of("11_as65001_b");
    RouterId c = RouterId.of("12_as65001_c");
    RouterId d = RouterId.of("13_as65001_d");
    RouterId e = RouterId.of("14_as65001_e");
    RouterId f = RouterId.of("15_as65001_f");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                a + ".cfg", ospfEndpoint("a", "10.1.0.0", 10),
                b + ".cfg", ospfEndpoint("b", "10.1.0.1", 10),
                c + ".cfg", ospfEndpoint("c", "10.2.0.0", 10),
                d + ".cfg", ospfEndpoint("d", "10.2.0.1", 10),
                e + ".cfg", ospfEndpoint("e", "10.3.0.0", 45),
                f + ".cfg", ospfEndpoint("f", "10.3.0.1", 45)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(topologyLink(a, b), topologyLink(c, d), topologyLink(e, f)));
    ProgramModel program = programs.model(snapshot, topology);
    AgreementOutlier outlier =
        AgreementOutlier.infer(program).stream()
            .filter(candidate -> candidate.instance().routers().containsAll(List.of(e, f)))
            .findFirst()
            .orElseThrow();
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());

    List<Hypothesis> hypotheses =
        closure.reviseAgreement(
            program, verification, outlier.instance(), outlier.requirement(), outlier.target());

    assertThat(hypotheses).isNotEmpty();
    assertThat(hypotheses)
        .allMatch(hypothesis -> hypothesis.routers().containsAll(List.of(e, f)))
        .allMatch(
            hypothesis -> {
              Snapshot repaired = snapshot.patch(hypothesis.transaction()).after();
              return repaired.text(e).text().contains("ip ospf cost 10")
                  && repaired.text(f).text().contains("ip ospf cost 10");
            });
  }

  @Test
  void everyBetterSupportedAgreementAlternativeIsMaterialised() {
    RouterId a = RouterId.of("10_as65001_a");
    RouterId b = RouterId.of("11_as65001_b");
    RouterId c = RouterId.of("12_as65001_c");
    RouterId d = RouterId.of("13_as65001_d");
    RouterId e = RouterId.of("14_as65001_e");
    RouterId f = RouterId.of("15_as65001_f");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                a + ".cfg", ospfEndpoint("a", "10.1.0.0", 10),
                b + ".cfg", ospfEndpoint("b", "10.1.0.1", 10),
                c + ".cfg", ospfEndpoint("c", "10.2.0.0", 20),
                d + ".cfg", ospfEndpoint("d", "10.2.0.1", 20),
                e + ".cfg", ospfEndpoint("e", "10.3.0.0", 45),
                f + ".cfg", ospfEndpoint("f", "10.3.0.1", 45)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(topologyLink(a, b), topologyLink(c, d), topologyLink(e, f)));
    ProgramModel program = programs.model(snapshot, topology);

    Set<List<String>> targets =
        AgreementOutlier.infer(program).stream()
            .filter(candidate -> candidate.instance().routers().containsAll(List.of(e, f)))
            .map(AgreementOutlier::target)
            .collect(java.util.stream.Collectors.toSet());

    assertThat(targets).containsExactlyInAnyOrder(List.of("10"), List.of("20"));
  }

  @Test
  void aPhysicalLinkProfileMovesItsExclusiveOspfCoverageWithTheAddress() {
    RouterId a = RouterId.of("10_as65001_a");
    RouterId b = RouterId.of("11_as65001_b");
    RouterId c = RouterId.of("12_as65001_c");
    RouterId d = RouterId.of("13_as65001_d");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                a + ".cfg", ospfEndpointWithNetwork("a", "10.181.0.156", "10.181.0.156"),
                b + ".cfg", ospfEndpointWithNetwork("b", "10.181.0.1", "10.181.0.0"),
                c + ".cfg", ospfEndpointWithNetwork("c", "10.181.0.2", "10.181.0.2"),
                d + ".cfg", ospfEndpointWithNetwork("d", "10.181.0.3", "10.181.0.2")));
    Topology topology =
        Topology.of(snapshot.routers(), List.of(topologyLink(a, b), topologyLink(c, d)));
    ProgramModel program = programs.model(snapshot, topology);
    RelationInstance target =
        program.instances("link").stream()
            .filter(link -> link.routers().containsAll(List.of(a, b)))
            .findFirst()
            .orElseThrow();

    List<Hypothesis> hypotheses = LinkProfileGenerator.generate(program, target);

    assertThat(hypotheses)
        .anyMatch(
            hypothesis -> {
              Snapshot repaired = snapshot.patch(hypothesis.transaction()).after();
              return repaired.text(a).text().contains("ip address 10.181.0.0 255.255.255.254")
                  && repaired.text(a).text().contains("network 10.181.0.0 0.0.0.1 area 0");
            });
  }

  private static String ospfEndpointWithNetwork(String hostname, String address, String network) {
    return """
        hostname %s
        interface GigabitEthernet0/0
         ip address %s 255.255.255.254
         ip ospf 1 area 0
        router ospf 1
         network %s 0.0.0.1 area 0
        """
        .formatted(hostname, address, network);
  }

  private static String ospfEndpoint(String hostname, String address, int cost) {
    return """
        hostname %s
        interface GigabitEthernet0/0
         ip address %s 255.255.255.254
         ip ospf 1 area 0
         ip ospf cost %d
         ip ospf network point-to-point
        router ospf 1
        """
        .formatted(hostname, address, cost);
  }

  private static Topology.Link topologyLink(RouterId left, RouterId right) {
    return new Topology.Link(
        new Topology.Endpoint(left, "GigabitEthernet0/0"),
        new Topology.Endpoint(right, "GigabitEthernet0/0"));
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

  @Test
  void noCandidateMovesARouterAwayFromItsDesignAutonomousSystem() {
    java.util.function.Function<String, RepairState> numbered =
        asn -> {
          Snapshot snapshot =
              Snapshot.of(
                  Map.of(
                      ONE + ".cfg",
                      "hostname r1\nrouter bgp "
                          + asn
                          + "\n address-family ipv4\n exit-address-family\n"));
          Topology topology = Topology.of(snapshot.routers(), List.of());
          return RepairState.initial(
              new RepairProblem("design", snapshot, topology, state.problem().specifications()),
              new Verification(
                  snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none()),
              programs.model(snapshot, topology));
        };
    java.util.function.BiFunction<RepairState, String, Hypothesis> renumbered =
        (from, asn) ->
            new Hypothesis(
                Transaction.of(
                    from.snapshot(),
                    List.of(
                        new LineEdit.Replace(
                            SourceSpan.line(ONE, 2), List.of("router bgp " + asn)))),
                List.of());

    RepairState designed = numbered.apply("65001");
    assertThat(admissibility.admit(designed, List.of(renumbered.apply(designed, "65002"))))
        .isEmpty();
    RepairState faulty = numbered.apply("65002");
    Hypothesis restored = renumbered.apply(faulty, "65001");
    assertThat(admissibility.admit(faulty, List.of(restored))).containsExactly(restored);
  }

  @Test
  void aSessionToARouterOffItsDesignAutonomousSystemIsRepairedAtThatRouter() {
    RouterId peer = RouterId.of("2_as65001_r2");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                ONE + ".cfg",
                """
                hostname r1
                interface GigabitEthernet0/0
                 ip address 10.1.0.0 255.255.255.254
                router bgp 65001
                 neighbor 10.1.0.1 remote-as 65001
                """,
                peer + ".cfg",
                """
                hostname r2
                interface GigabitEthernet0/0
                 ip address 10.1.0.1 255.255.255.254
                router bgp 65009
                 neighbor 10.1.0.0 remote-as 65001
                """));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    ProgramModel program = programs.model(snapshot, topology);
    RelationInstance session =
        program.instances("bgp-session").stream()
            .filter(instance -> instance.roles().get("local").router().equals(ONE))
            .findFirst()
            .orElseThrow();
    Requirement asn =
        session.violated().stream()
            .filter(requirement -> requirement.strength() == Requirement.Strength.HARD)
            .findFirst()
            .orElseThrow();

    List<Hypothesis> hypotheses =
        closure.close(
            program,
            new Verification(
                snapshot.id(), state.verification().verdict(), DataPlaneEvidence.none()),
            List.of(),
            Set.of(new ClosureEngine.Obligation(session, asn)));

    assertThat(hypotheses).isNotEmpty();
    assertThat(hypotheses)
        .as("r2's AS 65009 is not its design AS, so r1 does not follow it")
        .noneMatch(
            hypothesis ->
                snapshot
                    .patch(hypothesis.transaction())
                    .after()
                    .text(ONE)
                    .text()
                    .contains("remote-as 65009"));
  }

  @Test
  void redistributingThroughASessionPolicyBreaksASoftRequirement() {
    java.util.function.Function<String, List<String>> violated =
        map -> {
          Snapshot snapshot =
              Snapshot.of(
                  Map.of(
                      ONE + ".cfg",
                      """
                      hostname r1
                      router ospf 1
                       router-id 10.0.0.1
                       redistribute bgp 65001 subnets route-map %s
                      router bgp 65001
                       address-family ipv4
                       exit-address-family
                      route-map TRANSFER permit 10
                       set tag 65010
                      route-map IMPORT permit 10
                       set local-preference 100
                      """
                          .formatted(map)));
          return programs
              .model(snapshot, Topology.of(snapshot.routers(), List.of()))
              .instances("redistribution")
              .getFirst()
              .violated()
              .stream()
              .map(requirement -> requirement.strength() + " " + requirement.text())
              .toList();
        };

    assertThat(violated.apply("TRANSFER")).isEmpty();
    assertThat(violated.apply("IMPORT"))
        .containsExactly("SOFT soft absent router.ospf-redistribution.path-attributes");
  }

  private static Hypothesis hypothesisOf(List<EditOperator> seed) {
    try {
      return Hypothesis.of(state.snapshot(), seed);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
