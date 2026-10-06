package se.kth.sonar.prior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.BgpIncidenceSet;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.ForbiddenDelivery;
import se.kth.sonar.state.HypothesisDependency;
import se.kth.sonar.state.PolicyCut;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.state.TransitionLedger;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

/** Three routers in a line; r3's BGP neighbor names the wrong AS. */
class FactorsTest {
  private static final Dialect DIALECT = Dialect.load("cisco-ios");
  private static final RouterId R1 = RouterId.of("1_as65001_r1");
  private static final RouterId R2 = RouterId.of("2_as65001_r2");
  private static final RouterId R3 = RouterId.of("3_as65001_r3");
  private static final RouterId R4 = RouterId.of("4_as65001_r4");
  private static final Prefix LOOPBACK3 = Prefix.parse("10.0.0.3/32");
  private static final Specification BROKEN =
      new Specification(
          new ForwardingPredicate.Reachability(R1, LOOPBACK3),
          Specification.Status.parse("broken_removed"));
  private static final Specification INTACT =
      new Specification(
          new ForwardingPredicate.Reachability(R1, Prefix.parse("10.0.0.2/32")),
          Specification.Status.parse("intact"));
  private static ExecutionContext context;
  private static ProgramService programs;
  private static RepairState state;

  private static String config(int n, int peer, int remoteAs) {
    return """
        hostname r%d
        interface Loopback0
         ip address 10.0.0.%d 255.255.255.255
        interface GigabitEthernet0/0
         ip address 10.1.%d.1 255.255.255.0
        interface GigabitEthernet0/1
         ip address 10.2.%d.1 255.255.255.0
        router bgp 65001
         neighbor 10.0.0.%d remote-as %d
        """
        .formatted(n, n, n, n, peer, remoteAs);
  }

  @BeforeAll
  static void build() {
    context = ExecutionContext.open(ParallelismConfig.sequential(), List.of());
    programs = new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg", config(1, 2, 65001),
                R2 + ".cfg", config(2, 1, 65001),
                R3 + ".cfg", config(3, 2, 65009)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R2, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0"))));
    RepairProblem problem =
        new RepairProblem("line", snapshot, topology, SpecificationSet.of(List.of(BROKEN, INTACT)));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                BROKEN.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE)),
                INTACT.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1, R2), DataPlaneEvidence.Disposition.ACCEPTED))),
            Map.of(),
            Map.of(LOOPBACK3, Set.of(R3)));
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(BROKEN), List.of(), List.of()), evidence);
    state = RepairState.initial(problem, verification, programs.model(snapshot, topology));
  }

  @AfterAll
  static void close() {
    context.close();
  }

  private static Region site(RouterId router) {
    return new SiteRegion(
        new Region.Id("site:" + router),
        new ProgramObject.Ref("interface", router, List.of("Loopback0")),
        List.of(BROKEN),
        Map.of(),
        new Region.Hypotheses.Deferred(1));
  }

  @Test
  void spectrumFavoursRoutersOnFailingEvidence() {
    SpectrumFactor factor = new SpectrumFactor();
    double r1 = factor.weight(site(R1), state);
    double r2 = factor.weight(site(R2), state);
    double r3 = factor.weight(site(R3), state);
    assertThat(r3).isGreaterThan(r1);
    assertThat(r1).isCloseTo(r2, within(1e-12));
    assertThat(r3).isCloseTo(Math.sqrt(2.0 / 3 * 2.0 / 3), within(1e-12));
    assertThat(r1).isCloseTo(Math.sqrt(2.0 / 4 * 2.0 / 3), within(1e-12));
  }

  @Test
  void relationOddsRiseWithTheInstancesThatSatisfyTheRequirement() {
    RelationInstance violated =
        state.program().violated().stream()
            .filter(instance -> instance.relation().equals("bgp-session"))
            .findFirst()
            .orElseThrow();
    Region region =
        new RelationRegion(
            new Region.Id("relation"),
            violated,
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(4));
    RelationFactor factor = new RelationFactor(programs.evaluator());
    assertThat(factor.weight(region, state)).isCloseTo(1.5 / 1.5, within(1e-12));
    assertThat(factor.weight(site(R1), state)).isEqualTo(1);

    Hypothesis restoringCandidate =
        Hypothesis.of(
            state.snapshot(),
            List.of(
                new EditOperator.ReplaceSlots(
                    state.program().statements(R3).getLast(),
                    "asn",
                    List.of("65001"),
                    new Provenance.Forced("bgp-session", violated.violated().getFirst()))));
    Region expandedSite =
        site(R3).withHypotheses(new Region.Hypotheses.Admitted(List.of(restoringCandidate)));
    assertThat(factor.weight(expandedSite, state)).isEqualTo(factor.weight(site(R3), state));
  }

  @Test
  void agreedValuesUseOtherRelationInstancesAsLeaveOneOutEvidence() {
    RouterId a = RouterId.of("10_as65001_a");
    RouterId b = RouterId.of("11_as65001_b");
    RouterId c = RouterId.of("12_as65001_c");
    RouterId d = RouterId.of("13_as65001_d");
    RouterId e = RouterId.of("14_as65001_e");
    RouterId f = RouterId.of("15_as65001_f");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                a + ".cfg", ospfLink("a", "10.1.0.0", 10),
                b + ".cfg", ospfLink("b", "10.1.0.1", 10),
                c + ".cfg", ospfLink("c", "10.2.0.0", 10),
                d + ".cfg", ospfLink("d", "10.2.0.1", 10),
                e + ".cfg", ospfLink("e", "10.3.0.0", 45),
                f + ".cfg", ospfLink("f", "10.3.0.1", 45)));
    Topology topology =
        Topology.of(snapshot.routers(), List.of(link(a, b), link(c, d), link(e, f)));
    RepairProblem problem =
        new RepairProblem("metric", snapshot, topology, SpecificationSet.of(List.of(BROKEN)));
    RepairState metric =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(),
                new Verdict(List.of(BROKEN), List.of(), List.of()),
                new DataPlaneEvidence(Map.of(), Map.of(), Map.of())),
            programs.model(snapshot, topology));
    AgreementOutlierFactor factor = new AgreementOutlierFactor(programs.evaluator());
    Region unusual =
        new SiteRegion(
            new Region.Id("unusual"),
            new ProgramObject.Ref("interface", e, List.of("GigabitEthernet0/0")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region common =
        new SiteRegion(
            new Region.Id("common"),
            new ProgramObject.Ref("interface", a, List.of("GigabitEthernet0/0")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(factor.weight(unusual, metric)).isCloseTo(5, within(1e-12));
    assertThat(factor.weight(common, metric)).isEqualTo(1);
  }

  @Test
  void aMinorityFlagWithinOneStructuralRoleUsesLeaveOneOutOdds() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg",
                """
                hostname r1
                interface Loopback0
                 ip address 10.0.0.1 255.255.255.255
                router bgp 65001
                 neighbor IBGP peer-group
                 neighbor IBGP remote-as 65001
                 neighbor 10.0.0.2 peer-group IBGP
                 neighbor 10.0.0.3 peer-group IBGP
                 neighbor 10.0.0.4 peer-group IBGP
                 address-family ipv4
                  neighbor 10.0.0.2 activate
                  neighbor 10.0.0.2 next-hop-self
                  neighbor 10.0.0.3 activate
                  neighbor 10.0.0.3 next-hop-self
                  neighbor 10.0.0.4 activate
                 exit-address-family
                """));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    RepairProblem problem =
        new RepairProblem("flag-outlier", snapshot, topology, SpecificationSet.of(List.of(BROKEN)));
    RepairState outlier =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(),
                new Verdict(List.of(BROKEN), List.of(), List.of()),
                DataPlaneEvidence.none()),
            programs.model(snapshot, topology));
    AgreementOutlierFactor factor = new AgreementOutlierFactor(programs.evaluator());
    Region missing =
        new SiteRegion(
            new Region.Id("missing-next-hop-self"),
            new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.0.0.4")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region common =
        new SiteRegion(
            new Region.Id("common-next-hop-self"),
            new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.0.0.2")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(factor.weight(missing, outlier)).isCloseTo(5, within(1e-12));
    assertThat(factor.weight(common, outlier)).isEqualTo(1);
  }

  private static String ospfLink(String hostname, String address, int cost) {
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

  private static Topology.Link link(RouterId left, RouterId right) {
    return new Topology.Link(
        new Topology.Endpoint(left, "GigabitEthernet0/0"),
        new Topology.Endpoint(right, "GigabitEthernet0/0"));
  }

  @Test
  void frontierLinksBetweenTheNoRouteComponentAndTheOwnerCountObservations() {
    FrontierFactor factor = new FrontierFactor();
    Region spanning =
        new RelationRegion(
            new Region.Id("link"),
            state.program().instances("link").stream()
                .filter(instance -> instance.routers().containsAll(List.of(R2, R3)))
                .findFirst()
                .orElseThrow(),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(4));
    assertThat(factor.weight(spanning, state)).isCloseTo(6, within(1e-12));
    Region other =
        new RelationRegion(
            new Region.Id("other"),
            state.program().instances("link").stream()
                .filter(instance -> instance.routers().containsAll(List.of(R1, R2)))
                .findFirst()
                .orElseThrow(),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(4));
    assertThat(factor.weight(other, state)).isEqualTo(1);
    assertThat(factor.weight(site(R3), state)).isEqualTo(1);
  }

  /**
   * Only r3 knows the subnet of its link to r4, so the frontier is the link from r2 to r3. The
   * slice holds that link's interfaces and, as the origin of the route, r3's interface on the
   * destination link; a loopback of r3 originates nothing here.
   */
  @Test
  void aFrontierAtTheOwnerOfALinkSubnetHoldsThatLinksInterface() {
    Prefix linkSubnet = Prefix.parse("10.2.3.0/24");
    Specification isolated =
        new Specification(
            new ForwardingPredicate.Reachability(R1, linkSubnet),
            Specification.Status.parse("broken_removed"));
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg", config(1, 2, 65001),
                R2 + ".cfg", config(2, 1, 65001),
                R3 + ".cfg", config(3, 2, 65001),
                R4 + ".cfg", config(4, 3, 65001)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R2, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R3, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R4, "GigabitEthernet0/1"))));
    RepairProblem problem =
        new RepairProblem(
            "link-subnet", snapshot, topology, SpecificationSet.of(List.of(isolated)));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                isolated.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(),
            Map.of());
    RepairState unreached =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(isolated), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    java.util.function.Function<String, Region> onR3 =
        name ->
            new SiteRegion(
                new Region.Id("site:" + name),
                new ProgramObject.Ref("interface", R3, List.of(name)),
                List.of(isolated),
                Map.of(),
                new Region.Hypotheses.Deferred(1));
    FrontierFactor factor = new FrontierFactor();
    assertThat(factor.weight(onR3.apply("GigabitEthernet0/0"), unreached)).isGreaterThan(1);
    assertThat(factor.weight(onR3.apply("GigabitEthernet0/1"), unreached))
        .isEqualTo(factor.weight(onR3.apply("GigabitEthernet0/0"), unreached));
    assertThat(factor.weight(onR3.apply("Loopback0"), unreached)).isEqualTo(1);
  }

  /**
   * The path from r1 passes r2, which it must avoid, on its way to r3 in another autonomous system.
   * The link from r1 to r3 would take it past r2, and so would the session r1 has with r3.
   */
  @Test
  void aLinkBetweenAutonomousSystemsWeighsTheirSessionAsItself() {
    Prefix destination = Prefix.parse("10.200.0.0/24");
    Specification bypassing =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, destination, R2),
            Specification.Status.parse("broken_added"));
    RepairState fixture = bypassed(destination, bypassing, 65002);
    RepairState around =
        RepairState.initial(
            fixture.problem(),
            new Verification(
                fixture.snapshot().id(),
                new Verdict(List.of(bypassing), List.of(), List.of()),
                new DataPlaneEvidence(
                    Map.of(
                        bypassing.predicate(),
                        List.of(
                            new DataPlaneEvidence.Trace(
                                List.of(R1, R2, R3), DataPlaneEvidence.Disposition.ACCEPTED))),
                    Map.of(),
                    Map.of())),
            fixture.program());
    java.util.function.Function<ProgramObject.Ref, Region> region =
        ref ->
            new SiteRegion(
                new Region.Id("site:" + ref),
                ref,
                List.of(bypassing),
                Map.of(),
                new Region.Hypotheses.Deferred(1));

    PathDifferentialFactor factor = new PathDifferentialFactor();
    double link =
        factor.weight(
            region.apply(new ProgramObject.Ref("interface", R1, List.of("GigabitEthernet0/1"))),
            around);
    assertThat(link).isCloseTo(6, within(1e-12));
    assertThat(
            factor.weight(
                region.apply(new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.1.0.3"))),
                around))
        .isEqualTo(link);
    assertThat(
            factor.weight(
                region.apply(new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.1.0.1"))),
                around))
        .isEqualTo(1);
  }

  @Test
  void frontierEvidenceFlowsThroughTypedInterfaceAttachments() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg", config(1, 2, 65001),
                R2 + ".cfg",
                    config(2, 1, 65001)
                            .replace(
                                "interface GigabitEthernet0/1\n",
                                "interface GigabitEthernet0/1\n ip access-group BLOCK out\n")
                        + "ip access-list extended BLOCK\n 5 deny ip any any\n",
                R3 + ".cfg", config(3, 2, 65009)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R2, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0"))));
    RepairProblem problem =
        new RepairProblem("filter", snapshot, topology, SpecificationSet.of(List.of(BROKEN)));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                BROKEN.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(),
            Map.of(LOOPBACK3, Set.of(R3)));
    RepairState filtered =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(BROKEN), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    Region attachedAcl =
        new SiteRegion(
            new Region.Id("acl"),
            new ProgramObject.Ref("acl", R2, List.of("BLOCK")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region owner =
        new SiteRegion(
            new Region.Id("router"),
            new ProgramObject.Ref("router", R2, List.of()),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(RouterEvidence.of(filtered).routeFrontier(R1, LOOPBACK3))
        .containsExactly(RouterEvidence.Link.of(R2, R3));
    assertThat(new FrontierFactor().weight(attachedAcl, filtered)).isCloseTo(6, within(1e-12));
    assertThat(new FrontierFactor().weight(owner, filtered)).isEqualTo(1);
  }

  @Test
  void routeFrontierStopsAtTheNearestLayerThatKnowsTheRoute() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg", config(1, 2, 65001),
                R2 + ".cfg", config(2, 1, 65001),
                R3 + ".cfg", config(3, 1, 65001),
                R4 + ".cfg", config(4, 2, 65001)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R2, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R4, "GigabitEthernet0/0"))));
    Specification target =
        new Specification(new ForwardingPredicate.Reachability(R1, LOOPBACK3), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(),
            Map.of(LOOPBACK3, Set.of(R3, R4)));
    RepairProblem problem =
        new RepairProblem("nearest", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState nearest =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));

    assertThat(RouterEvidence.of(nearest).routeFrontier(R1, LOOPBACK3))
        .containsExactly(RouterEvidence.Link.of(R1, R3));
  }

  @Test
  void dropPointsWeighTheRouterThatDeniedTheFlow() {
    Specification denied =
        new Specification(
            new ForwardingPredicate.Reachability(R3, Prefix.parse("10.0.0.1/32")),
            Specification.Status.parse("broken_removed"));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                denied.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R3, R2), DataPlaneEvidence.Disposition.DENIED))),
            Map.of(),
            Map.of());
    Verification verification =
        new Verification(
            state.snapshot().id(), new Verdict(List.of(denied), List.of(), List.of()), evidence);
    RepairState dropped = RepairState.initial(state.problem(), verification, state.program());
    DropFactor factor = new DropFactor();
    assertThat(factor.weight(site(R2), dropped)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(site(R3), dropped)).isEqualTo(1);
    assertThat(factor.weight(site(R2), state)).isEqualTo(1);
  }

  @Test
  void dropPointsCountOnlyFlowsThatMustBeDelivered() {
    Prefix destination = Prefix.parse("10.0.0.1/32");
    Map<String, Boolean> counted = Map.of("broken_added", true, "broken_removed", false);
    counted.forEach(
        (label, counts) -> {
          Specification isolation =
              new Specification(
                  new ForwardingPredicate.Isolation(R3, destination),
                  Specification.Status.parse(label));
          DataPlaneEvidence evidence =
              new DataPlaneEvidence(
                  Map.of(
                      isolation.predicate(),
                      List.of(
                          new DataPlaneEvidence.Trace(
                              List.of(R3, R2), DataPlaneEvidence.Disposition.DENIED))),
                  Map.of(),
                  Map.of());
          Verification verification =
              new Verification(
                  state.snapshot().id(),
                  new Verdict(List.of(isolation), List.of(), List.of()),
                  evidence);
          RepairState dropped = RepairState.initial(state.problem(), verification, state.program());
          assertThat(new DropFactor().weight(site(R2), dropped))
              .as(label)
              .isCloseTo(counts ? 3 : 1, within(1e-12));
        });
  }

  @Test
  void counterexamplesWeighOnlyTheExactRouterRelation() {
    Specification waypoint =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, LOOPBACK3, R3),
            Specification.Status.parse("broken_removed"));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                waypoint.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1, R2), DataPlaneEvidence.Disposition.ACCEPTED))),
            Map.of(),
            Map.of());
    Verification verification =
        new Verification(
            state.snapshot().id(), new Verdict(List.of(waypoint), List.of(), List.of()), evidence);
    RepairState counterexample =
        RepairState.initial(state.problem(), verification, state.program());
    CounterexampleFactor factor = new CounterexampleFactor();
    RelationInstance instance = state.program().instances("link").getFirst();
    Region exact =
        new RelationRegion(
            new Region.Id("exact"),
            new RelationInstance(
                instance.relation(),
                new java.util.LinkedHashMap<>(
                    Map.of(
                        "a", new ProgramObject.Ref("router", R1, List.of()),
                        "b", new ProgramObject.Ref("router", R3, List.of()))),
                List.of()),
            List.of(waypoint),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    assertThat(factor.weight(exact, counterexample)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(site(R2), counterexample)).isEqualTo(1);
  }

  @Test
  void pathDifferentialsWeighOnlyLinksThatDistinguishWaypointOutcomes() {
    Specification waypoint =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, LOOPBACK3, R2),
            Specification.Status.parse("broken_removed"));
    Verification verification =
        new Verification(
            state.snapshot().id(),
            new Verdict(List.of(waypoint), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    waypoint.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(R1, R2, R3), DataPlaneEvidence.Disposition.ACCEPTED),
                        new DataPlaneEvidence.Trace(
                            List.of(R1, R3), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState differential = RepairState.initial(state.problem(), verification, state.program());
    RelationInstance first =
        state.program().instances("link").stream()
            .filter(instance -> instance.routers().containsAll(List.of(R1, R2)))
            .findFirst()
            .orElseThrow();
    Region exact =
        new RelationRegion(
            new Region.Id("differential-link"),
            first,
            List.of(waypoint),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region broad =
        new SiteRegion(
            new Region.Id("broad-router"),
            new ProgramObject.Ref("router", R2, List.of()),
            List.of(waypoint),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    PathDifferentialFactor factor = new PathDifferentialFactor();
    assertThat(factor.weight(exact, differential)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(broad, differential)).isEqualTo(1);
  }

  @Test
  void pathDifferentialsRespectAForbiddenWaypoint() {
    Specification waypoint =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, LOOPBACK3, R2),
            Specification.Status.parse("broken_added"));
    Verification verification =
        new Verification(
            state.snapshot().id(),
            new Verdict(List.of(waypoint), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    waypoint.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(R1, R2, R3), DataPlaneEvidence.Disposition.ACCEPTED),
                        new DataPlaneEvidence.Trace(
                            List.of(R1, R3), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState differential = RepairState.initial(state.problem(), verification, state.program());
    RelationInstance first =
        state.program().instances("link").stream()
            .filter(instance -> instance.routers().containsAll(List.of(R1, R2)))
            .findFirst()
            .orElseThrow();
    Region exact =
        new RelationRegion(
            new Region.Id("forbidden-waypoint-link"),
            first,
            List.of(waypoint),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(new PathDifferentialFactor().weight(exact, differential))
        .isCloseTo(3, within(1e-12));
  }

  /**
   * In a triangle, a path through r2, which it must avoid, names the direct link from r1 to r3 as
   * the way around it. A direct path that must visit r2 names no link.
   */
  @Test
  void aViolatingPathNamesTheLinksItDidNotTake() {
    Topology triangle =
        Topology.of(
            state.snapshot().routers(),
            List.of(
                link(R1, R2),
                new Topology.Link(
                    new Topology.Endpoint(R2, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/1"))));
    ProgramModel program = programs.model(state.snapshot(), triangle);
    java.util.function.BiFunction<String, List<RouterId>, RepairState> violated =
        (status, path) -> {
          Specification waypoint =
              new Specification(
                  new ForwardingPredicate.Waypointing(R1, LOOPBACK3, R2),
                  Specification.Status.parse(status));
          return RepairState.initial(
              new RepairProblem(
                  "triangle", state.snapshot(), triangle, SpecificationSet.of(List.of(waypoint))),
              new Verification(
                  state.snapshot().id(),
                  new Verdict(List.of(waypoint), List.of(), List.of()),
                  new DataPlaneEvidence(
                      Map.of(
                          waypoint.predicate(),
                          List.of(
                              new DataPlaneEvidence.Trace(
                                  path, DataPlaneEvidence.Disposition.ACCEPTED))),
                      Map.of(),
                      Map.of())),
              program);
        };
    java.util.function.BiFunction<RouterId, RouterId, Region> between =
        (left, right) ->
            new RelationRegion(
                new Region.Id("link:" + left + ":" + right),
                program.instances("link").stream()
                    .filter(instance -> instance.routers().containsAll(List.of(left, right)))
                    .findFirst()
                    .orElseThrow(),
                List.of(),
                Map.of(),
                new Region.Hypotheses.Deferred(1));
    PathDifferentialFactor factor = new PathDifferentialFactor();

    RepairState through = violated.apply("broken_added", List.of(R1, R2, R3));
    assertThat(factor.weight(between.apply(R1, R3), through)).isCloseTo(9, within(1e-12));
    assertThat(factor.weight(between.apply(R1, R2), through)).isEqualTo(1);

    RepairState around = violated.apply("broken_removed", List.of(R1, R3));
    assertThat(factor.weight(between.apply(R1, R2), around)).isEqualTo(1);
    assertThat(factor.weight(between.apply(R2, R3), around)).isEqualTo(1);
    assertThat(factor.weight(between.apply(R1, R3), around)).isEqualTo(1);
  }

  @Test
  void aFormalWaypointPairWeighsItsExactBgpSessionObjects() {
    Specification waypoint =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, LOOPBACK3, R2),
            Specification.Status.parse("broken_removed"));
    Verification verification =
        new Verification(
            state.snapshot().id(),
            new Verdict(List.of(waypoint), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    waypoint.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(R1), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState counterexample =
        RepairState.initial(state.problem(), verification, state.program());
    Region session =
        new SiteRegion(
            new Region.Id("waypoint-session"),
            new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.0.0.2")),
            List.of(waypoint),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(new CounterexampleFactor().weight(session, counterexample)).isGreaterThan(3);
  }

  @Test
  void aFormalWaypointPairWeighsAnExactMissingBgpIncidence() {
    Specification waypoint =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, LOOPBACK3, R3),
            Specification.Status.parse("broken_removed"));
    Verification verification =
        new Verification(
            state.snapshot().id(),
            new Verdict(List.of(waypoint), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    waypoint.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
                Map.of(),
                Map.of()));
    RepairState counterexample =
        RepairState.initial(state.problem(), verification, state.program());
    ProgramObject.Ref reflector = new ProgramObject.Ref("router", R1, List.of());
    ProgramObject.Ref client = new ProgramObject.Ref("router", R3, List.of());
    ProgramObject.Ref template = new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.0.0.2"));
    BgpIncidenceSet incidence =
        new BgpIncidenceSet(
            reflector,
            List.of(client),
            List.of(
                new BgpIncidenceSet.Direction(
                    reflector,
                    client,
                    "10.0.0.3",
                    List.of(new BgpIncidenceSet.Group("IBGP", template, true)))));
    Region missing =
        new IncidenceRegion(
            new Region.Id("missing-waypoint-session"),
            incidence,
            List.of(waypoint),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(new CounterexampleFactor().weight(missing, counterexample)).isGreaterThan(3);
  }

  /**
   * Returns a state in which r1 delivers to {@code destination} through r3 although r2, in the
   * autonomous system {@code waypointAs}, is a required waypoint.
   */
  private static RepairState bypassed(Prefix destination, Specification target, int waypointAs) {
    return bypassed(destination, target, waypointAs, Map.of());
  }

  /**
   * Returns the bypassed state in which the specifications of {@code delivered} hold as well, each
   * with its traffic accepted along the given path.
   */
  private static RepairState bypassed(
      Prefix destination,
      Specification target,
      int waypointAs,
      Map<Specification, List<RouterId>> delivered) {
    String source =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.1.0.2 255.255.255.254
        route-map IN_2 permit 10
         set local-preference 99
        route-map OUT_2 permit 10
        route-map IN_3 permit 10
         set local-preference 100
        router bgp 65001
         neighbor 10.1.0.1 remote-as %d
         neighbor 10.1.0.3 remote-as 65003
         address-family ipv4
          neighbor 10.1.0.1 activate
          neighbor 10.1.0.1 route-map IN_2 in
          neighbor 10.1.0.1 route-map OUT_2 out
          neighbor 10.1.0.3 activate
          neighbor 10.1.0.3 route-map IN_3 in
         exit-address-family
        """
            .formatted(waypointAs);
    String waypoint =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        ip prefix-list LINKS seq 10 permit 10.1.0.0/16 le 32
        route-map OUT_1 deny 10
         match ip address prefix-list LINKS
        route-map OUT_1 permit 20
         set metric 30
        route-map OUT_1 deny 30
        router bgp %d
         neighbor 10.1.0.0 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.0 activate
          neighbor 10.1.0.0 route-map OUT_1 out
         exit-address-family
        """
            .formatted(waypointAs);
    String taken =
        """
        hostname r3
        interface GigabitEthernet0/0
         ip address 10.1.0.3 255.255.255.254
        router bgp 65003
         neighbor 10.1.0.2 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.2 activate
         exit-address-family
        """;
    Snapshot snapshot =
        Snapshot.of(Map.of(R1 + ".cfg", source, R2 + ".cfg", waypoint, R3 + ".cfg", taken));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0"))));
    Map<ForwardingPredicate, List<DataPlaneEvidence.Trace>> traces = new HashMap<>();
    traces.put(
        target.predicate(),
        List.of(
            new DataPlaneEvidence.Trace(List.of(R1, R3), DataPlaneEvidence.Disposition.ACCEPTED)));
    delivered.forEach(
        (specification, path) ->
            traces.put(
                specification.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(path, DataPlaneEvidence.Disposition.ACCEPTED))));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            traces,
            Map.of(destination, Set.of(R1, R2, R3)),
            Map.of(destination, Set.of(R1, R2, R3)));
    List<Specification> specifications = new ArrayList<>(List.of(target));
    specifications.addAll(delivered.keySet());
    RepairProblem problem =
        new RepairProblem("bypass", snapshot, topology, SpecificationSet.of(specifications));
    return RepairState.initial(
        problem,
        new Verification(
            snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
        programs.model(snapshot, topology));
  }

  /**
   * The flow leaves r1 for r3, so r3 exported the route and r1 imported it: the slice is the two
   * ends of that session and the clause of r1's import map that decides the destination, three of
   * the ten neighbor and clause objects.
   */
  @Test
  void aForbiddenDeliveryWeighsThePoliciesThatLetItsRouteAcrossAnExternalSession() {
    Prefix destination = Prefix.parse("10.200.0.0/24");
    Specification unwanted =
        new Specification(
            new ForwardingPredicate.Reachability(R1, destination),
            Specification.Status.parse("broken_added"));
    RepairState bypassed = bypassed(destination, unwanted, 65002);
    java.util.function.Function<ProgramObject.Ref, Region> region =
        ref ->
            new SiteRegion(
                new Region.Id("site:" + ref),
                ref,
                List.of(unwanted),
                Map.of(),
                new Region.Hypotheses.Deferred(1));

    CounterexampleFactor factor = new CounterexampleFactor();
    double exact = 10.0 / 3 * 3;
    for (ProgramObject.Ref onThePath :
        List.of(
            new ProgramObject.Ref("bgp-neighbor", R3, List.of("10.1.0.2")),
            new ProgramObject.Ref("route-map-entry", R1, List.of("IN_3", "10")))) {
      assertThat(factor.weight(region.apply(onThePath), bypassed)).isCloseTo(exact, within(1e-12));
    }
    for (ProgramObject.Ref offThePath :
        List.of(
            new ProgramObject.Ref("route-map-entry", R1, List.of("IN_2", "10")),
            new ProgramObject.Ref("route-map-entry", R2, List.of("OUT_1", "20")),
            new ProgramObject.Ref("bgp-neighbor", R2, List.of("10.1.0.0")))) {
      assertThat(factor.weight(region.apply(offThePath), bypassed)).isEqualTo(1);
    }

    Specification wanted =
        new Specification(
            new ForwardingPredicate.Reachability(R2, destination),
            Specification.Status.parse("intact"));
    RepairState shared =
        bypassed(destination, unwanted, 65002, Map.of(wanted, List.of(R2, R1, R3)));
    assertThat(ForbiddenDelivery.policies(bypassed)).hasSize(3);
    assertThat(ForbiddenDelivery.policies(shared))
        .as("r2 must be delivered over the same session, so the route has to cross it")
        .isEmpty();
  }

  @Test
  void aForbiddenDestinationThatServesNobodyWeighsWhatOriginatesIt() {
    Specification unwanted =
        new Specification(
            new ForwardingPredicate.Reachability(R1, LOOPBACK3),
            Specification.Status.parse("broken_added"));
    Specification wanted =
        new Specification(
            new ForwardingPredicate.Reachability(R2, LOOPBACK3),
            Specification.Status.parse("intact"));
    ProgramObject.Ref origin = new ProgramObject.Ref("interface", R3, List.of("Loopback0"));
    Region region =
        new SiteRegion(
            new Region.Id("site:" + origin),
            origin,
            List.of(unwanted),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    RepairState unserved = delivered(List.of(unwanted), unwanted);
    assertThat(ForbiddenDelivery.origins(unserved)).containsExactly(Map.entry(origin, 1));
    assertThat(new CounterexampleFactor().weight(region, unserved)).isCloseTo(3, within(1e-12));

    RepairState served = delivered(List.of(unwanted, wanted), unwanted);
    assertThat(ForbiddenDelivery.origins(served)).isEmpty();
    assertThat(new CounterexampleFactor().weight(region, served)).isEqualTo(1);
  }

  /**
   * Returns the shared network with {@code specifications}, of which {@code unresolved} is violated
   * because r1 delivers to its destination through r2 and r3.
   */
  private static RepairState delivered(
      List<Specification> specifications, Specification unresolved) {
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                unresolved.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1, R2, R3), DataPlaneEvidence.Disposition.ACCEPTED))),
            Map.of(),
            Map.of());
    RepairProblem problem =
        new RepairProblem(
            "delivered",
            state.snapshot(),
            state.problem().topology(),
            SpecificationSet.of(specifications));
    return RepairState.initial(
        problem,
        new Verification(
            state.snapshot().id(),
            new Verdict(List.of(unresolved), List.of(), List.of()),
            evidence),
        state.program());
  }

  /**
   * Four neighbors and six clauses narrow to the four objects of the slice: the two ends of the
   * session and the clause that decides the destination in the hop's import map and in the
   * waypoint's export map.
   */
  @Test
  void aBypassedWaypointWeighsThePoliciesOfItsExternalSessionWithThePath() {
    Prefix destination = Prefix.parse("10.200.0.0/24");
    Specification target =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, destination, R2),
            Specification.Status.parse("broken_removed"));
    RepairState bypassed = bypassed(destination, target, 65002);
    RepairState internal = bypassed(destination, target, 65001);
    java.util.function.Function<ProgramObject.Ref, Region> region =
        ref ->
            new SiteRegion(
                new Region.Id("site:" + ref),
                ref,
                List.of(target),
                Map.of(),
                new Region.Hypotheses.Deferred(1));

    CounterexampleFactor factor = new CounterexampleFactor();
    double exact = 10.0 / 4 * 3;
    for (ProgramObject.Ref onTheSlice :
        List.of(
            new ProgramObject.Ref("bgp-neighbor", R2, List.of("10.1.0.0")),
            new ProgramObject.Ref("route-map-entry", R1, List.of("IN_2", "10")),
            new ProgramObject.Ref("route-map-entry", R2, List.of("OUT_1", "20")))) {
      assertThat(factor.weight(region.apply(onTheSlice), bypassed)).isCloseTo(exact, within(1e-12));
    }
    for (ProgramObject.Ref policy :
        List.of(
            new ProgramObject.Ref("route-map-entry", R1, List.of("IN_2", "10")),
            new ProgramObject.Ref("route-map-entry", R2, List.of("OUT_1", "20")))) {
      assertThat(factor.weight(region.apply(policy), internal)).isEqualTo(1);
    }
    for (ProgramObject.Ref offTheSlice :
        List.of(
            new ProgramObject.Ref("route-map-entry", R1, List.of("OUT_2", "10")),
            new ProgramObject.Ref("route-map-entry", R1, List.of("IN_3", "10")),
            new ProgramObject.Ref("route-map-entry", R2, List.of("OUT_1", "10")),
            new ProgramObject.Ref("route-map-entry", R2, List.of("OUT_1", "30")))) {
      assertThat(factor.weight(region.apply(offTheSlice), bypassed)).isEqualTo(1);
    }
  }

  @Test
  void brokenAddedReachabilityWeighsTheAcceptedFirstHopPolicyObjects() {
    Specification forbidden =
        new Specification(
            new ForwardingPredicate.Reachability(R3, Prefix.parse("10.0.0.1/32")),
            Specification.Status.parse("broken_added"));
    Verification verification =
        new Verification(
            state.snapshot().id(),
            new Verdict(List.of(forbidden), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    forbidden.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(R3, R2, R1), DataPlaneEvidence.Disposition.ACCEPTED))),
                Map.of(),
                Map.of()));
    RepairState counterexample =
        RepairState.initial(state.problem(), verification, state.program());
    Region firstHop =
        new SiteRegion(
            new Region.Id("first-hop"),
            new ProgramObject.Ref("bgp-neighbor", R3, List.of("10.0.0.2")),
            List.of(forbidden),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region unrelated =
        new SiteRegion(
            new Region.Id("unrelated"),
            new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.0.0.2")),
            List.of(forbidden),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(firstHop, counterexample)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(unrelated, counterexample)).isEqualTo(1);
  }

  @Test
  void noRouteCounterexamplesWeighTheExactDestinationOrigin() {
    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(site(R3), state)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(site(R2), state)).isEqualTo(1);
  }

  /**
   * Nothing originates the default destination and no router has it, so the missing route of r1 is
   * left to r1's own program: its router object, and more precisely the relations of that object,
   * r1 being one of three routers.
   */
  @Test
  void aMissingRouteThatNothingOriginatesWeighsTheSourcesOwnProgram() {
    Specification missing =
        new Specification(
            new ForwardingPredicate.Reachability(R1, Prefix.parse("0.0.0.0/0")),
            Specification.Status.parse("broken_removed"));
    RepairState unexplained =
        RepairState.initial(
            new RepairProblem(
                "default",
                state.snapshot(),
                state.problem().topology(),
                SpecificationSet.of(List.of(missing))),
            new Verification(
                state.snapshot().id(),
                new Verdict(List.of(missing), List.of(), List.of()),
                new DataPlaneEvidence(
                    Map.of(
                        missing.predicate(),
                        List.of(
                            new DataPlaneEvidence.Trace(
                                List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
                    Map.of(),
                    Map.of())),
            state.program());
    java.util.function.Function<RouterId, Region> design =
        router ->
            new RelationRegion(
                new Region.Id("design:" + router),
                state.program().instances("bgp-design").stream()
                    .filter(instance -> instance.routers().contains(router))
                    .findFirst()
                    .orElseThrow(),
                List.of(missing),
                Map.of(),
                new Region.Hypotheses.Deferred(1));

    java.util.function.Function<RouterId, Region> program =
        router ->
            new SiteRegion(
                new Region.Id("router:" + router),
                new ProgramObject.Ref("router", router, List.of()),
                List.of(missing),
                Map.of(),
                new Region.Hypotheses.Deferred(1));

    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(program.apply(R1), unexplained)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(design.apply(R1), unexplained)).isCloseTo(9, within(1e-12));
    assertThat(factor.weight(program.apply(R2), unexplained)).isEqualTo(1);
    assertThat(factor.weight(design.apply(R2), unexplained)).isEqualTo(1);
  }

  /**
   * The interfaces of the shared network are on 24-bit subnets. A destination of 25 bits inside the
   * subnet of r3's link interface is originated by nothing, and would be by that interface.
   */
  @Test
  void aMissingRouteThatNothingOriginatesExactlyWeighsTheInterfaceOnAnotherMask() {
    Specification missing =
        new Specification(
            new ForwardingPredicate.Reachability(R1, Prefix.parse("10.1.3.0/25")),
            Specification.Status.parse("broken_removed"));
    RepairState unoriginated =
        RepairState.initial(
            new RepairProblem(
                "mask",
                state.snapshot(),
                state.problem().topology(),
                SpecificationSet.of(List.of(missing))),
            new Verification(
                state.snapshot().id(),
                new Verdict(List.of(missing), List.of(), List.of()),
                new DataPlaneEvidence(
                    Map.of(
                        missing.predicate(),
                        List.of(
                            new DataPlaneEvidence.Trace(
                                List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
                    Map.of(),
                    Map.of())),
            state.program());
    java.util.function.BiFunction<RouterId, String, Region> region =
        (router, name) ->
            new SiteRegion(
                new Region.Id("interface:" + router + ":" + name),
                new ProgramObject.Ref("interface", router, List.of(name)),
                List.of(missing),
                Map.of(),
                new Region.Hypotheses.Deferred(1));

    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(region.apply(R3, "GigabitEthernet0/0"), unoriginated))
        .isCloseTo(3, within(1e-12));
    assertThat(factor.weight(region.apply(R3, "GigabitEthernet0/1"), unoriginated)).isEqualTo(1);
    assertThat(factor.weight(region.apply(R2, "GigabitEthernet0/0"), unoriginated)).isEqualTo(1);
  }

  @Test
  void noRouteCounterexamplesWeighTheLoopbackOriginationOfTheDestination() {
    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(loopbackOrigination(R3), state)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(loopbackOrigination(R2), state)).isEqualTo(1);
  }

  private static Region loopbackOrigination(RouterId router) {
    ProgramObject.Ref loopback = new ProgramObject.Ref("interface", router, List.of("Loopback0"));
    RelationInstance origination =
        state.program().instances("ospf-loopback-origination").stream()
            .filter(instance -> instance.roles().containsValue(loopback))
            .findFirst()
            .orElseThrow();
    return new RelationRegion(
        new Region.Id("loopback-origination:" + router),
        origination,
        List.of(BROKEN),
        Map.of(),
        new Region.Hypotheses.Deferred(1));
  }

  @Test
  void aSessionToAPeerHoldingTheRouteIsAFrontierWithoutAPhysicalLink() {
    // r3 peers with r1, r2 and r4 in the same way; r2 and r4 hold the route and r1 does not
    RepairState routeless = sessionState(Set.of(R2, R3, R4), 3);
    Prefix destination = SESSION_DESTINATION;
    Specification missing = routeless.verification().verdict().unresolved().getFirst();
    Region session =
        new SiteRegion(
            new Region.Id("session"),
            new ProgramObject.Ref("bgp-neighbor", R3, List.of("10.0.0.1")),
            List.of(missing),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region beyond =
        new SiteRegion(
            new Region.Id("beyond"),
            new ProgramObject.Ref("interface", R2, List.of("GigabitEthernet0/1")),
            List.of(missing),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(RouterEvidence.of(routeless).sessionFrontier(R1, destination))
        .containsExactly(RouterEvidence.Link.of(R1, R3));
    assertThat(RouterEvidence.of(routeless).routers(missing)).contains(R3);
    // One frontier session among the three: reduction 3, odds 3
    assertThat(new FrontierFactor().weight(session, routeless)).isCloseTo(9, within(1e-12));
    assertThat(new FrontierFactor().weight(beyond, routeless)).isEqualTo(1);
  }

  @Test
  void aHolderThatFailsEverySessionOfAKindIsNoSessionFrontier() {
    // r3 holds the route but passes it to none of its peers: the cause is what they share
    RepairState routeless = sessionState(Set.of(R3), 3);

    assertThat(RouterEvidence.of(routeless).sessionFrontier(R1, SESSION_DESTINATION)).isEmpty();
  }

  @Test
  void aSourceThatTakesTheRouteFromNoneOfSeveralHoldersIsNoSessionFrontier() {
    // r1 peers with r3 and r4, which both hold the route: the cause is on r1
    RepairState routeless = sessionState(Set.of(R2, R3, R4), 1);

    assertThat(RouterEvidence.of(routeless).sessionFrontier(R1, SESSION_DESTINATION)).isEmpty();
  }

  private static final Prefix SESSION_DESTINATION = Prefix.parse("10.9.9.0/24");

  /**
   * Returns r1 without a route to {@link #SESSION_DESTINATION}, peering with r3 across r2; r3 also
   * peers with r2 and r4, r4 peers with {@code r4Peer}, and {@code holders} hold the route.
   */
  private static RepairState sessionState(Set<RouterId> holders, int r4Peer) {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg", config(1, 3, 65001),
                R2 + ".cfg", config(2, 3, 65001),
                R3 + ".cfg",
                    config(3, 1, 65001)
                        + " neighbor 10.0.0.2 remote-as 65001\n neighbor 10.0.0.4 remote-as 65001\n",
                R4 + ".cfg", config(4, r4Peer, 65001)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R2, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0"))));
    Specification missing =
        new Specification(
            new ForwardingPredicate.Reachability(R1, SESSION_DESTINATION),
            Specification.Status.parse("broken_removed"));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                missing.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(SESSION_DESTINATION, holders),
            Map.of(SESSION_DESTINATION, Set.of(R2, R3)));
    return RepairState.initial(
        new RepairProblem(
            "reflected-without-next-hop",
            snapshot,
            topology,
            SpecificationSet.of(List.of(missing))),
        new Verification(
            snapshot.id(), new Verdict(List.of(missing), List.of(), List.of()), evidence),
        programs.model(snapshot, topology));
  }

  @Test
  void noRouteCounterexamplesWeighTheExactBoundarySession() {
    Region boundarySession =
        new SiteRegion(
            new Region.Id("boundary-session"),
            new ProgramObject.Ref("bgp-neighbor", R3, List.of("10.0.0.2")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region unrelatedSession =
        new SiteRegion(
            new Region.Id("unrelated-session"),
            new ProgramObject.Ref("bgp-neighbor", R1, List.of("10.0.0.2")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    RelationInstance session =
        state.program().instances("bgp-session").stream()
            .filter(
                instance ->
                    instance.roles().values().stream()
                        .anyMatch(
                            role ->
                                role.equals(
                                    new ProgramObject.Ref(
                                        "bgp-neighbor", R3, List.of("10.0.0.2")))))
            .findFirst()
            .orElseThrow();
    Region boundaryRelation =
        new RelationRegion(
            new Region.Id("boundary-relation"),
            session,
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region broadSource =
        new SiteRegion(
            new Region.Id("broad-source"),
            new ProgramObject.Ref("router", R1, List.of()),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(boundarySession, state)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(boundaryRelation, state)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(unrelatedSession, state)).isEqualTo(1);
    assertThat(factor.weight(broadSource, state)).isEqualTo(1);
    assertThat(RouterEvidence.of(state).unresolvedByRouter()).containsKeys(R1, R2, R3);
  }

  @Test
  void requiredIsolationDoesNotInventARoutePropagationBoundary() {
    Specification isolation =
        new Specification(
            new ForwardingPredicate.Isolation(R1, LOOPBACK3),
            Specification.Status.parse("broken_removed"));
    Verification verification =
        new Verification(
            state.snapshot().id(),
            new Verdict(List.of(isolation), List.of(), List.of()),
            new DataPlaneEvidence(
                Map.of(
                    isolation.predicate(),
                    List.of(
                        new DataPlaneEvidence.Trace(
                            List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
                Map.of(),
                Map.of(LOOPBACK3, Set.of(R3))));
    RepairState isolated = RepairState.initial(state.problem(), verification, state.program());
    Region boundarySession =
        new SiteRegion(
            new Region.Id("boundary-session"),
            new ProgramObject.Ref("bgp-neighbor", R3, List.of("10.0.0.2")),
            List.of(isolation),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(new CounterexampleFactor().weight(boundarySession, isolated)).isEqualTo(1);
    assertThat(RouterEvidence.of(isolated).unresolvedByRouter()).doesNotContainKey(R2);
  }

  @Test
  void aMissingBgpOriginWeighsSamePrefixOriginStatementsOnTheOwner() {
    Prefix destination = Prefix.parse("10.200.0.0/24");
    Specification target =
        new Specification(new ForwardingPredicate.Reachability(R1, destination), BROKEN.status());
    String origin =
        """
        hostname r3
        interface Loopback100
         ip address 10.200.0.1 255.255.255.0
        interface Loopback101
         ip address 10.200.1.1 255.255.255.0
        router bgp 65001
         address-family ipv4
          network 10.200.1.0 mask 255.255.255.0
          network 10.0.0.3 mask 255.255.255.255
         exit-address-family
        ip route 10.200.0.0 255.255.255.0 Loopback100
        """;
    Snapshot snapshot = Snapshot.of(Map.of(R1 + ".cfg", config(1, 2, 65001), R3 + ".cfg", origin));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(),
            Map.of());
    RepairProblem problem =
        new RepairProblem(
            "missing-origin", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState missing =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    Region samePrefix =
        new SiteRegion(
            new Region.Id("same-prefix"),
            new ProgramObject.Ref("bgp-network", R3, List.of("10.200.1.0", "255.255.255.0")),
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(1));
    Region otherPrefix =
        new SiteRegion(
            new Region.Id("other-prefix"),
            new ProgramObject.Ref("bgp-network", R3, List.of("10.0.0.3", "255.255.255.255")),
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(samePrefix, missing)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(otherPrefix, missing)).isEqualTo(1);
  }

  @Test
  void routeFrontierAndRibEvidenceWeighTheExactRedistributionPolicy() {
    String source =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
         ip ospf 1 area 0
        router ospf 1
        """;
    String holder =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
         ip ospf 1 area 0
        router ospf 1
        router bgp 65001
         address-family ipv4
         exit-address-family
        ip prefix-list EXPORTS seq 10 permit 10.200.0.0/16 le 24
        route-map EXPORT permit 10
         match ip address prefix-list EXPORTS
        """;
    Snapshot snapshot = Snapshot.of(Map.of(R1 + ".cfg", source, R2 + ".cfg", holder));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0"))));
    Prefix destination = Prefix.parse("10.200.27.0/24");
    Specification target =
        new Specification(new ForwardingPredicate.Reachability(R1, destination), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(destination, Set.of(R2)),
            Map.of(destination, Set.of(R2)));
    RepairProblem problem =
        new RepairProblem("transfer", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState transfer =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    RelationInstance liveness =
        transfer.program().instances("route-map-liveness").stream()
            .filter(instance -> instance.roles().get("map").key().equals(List.of("EXPORT")))
            .findFirst()
            .orElseThrow();
    Region region =
        new RelationRegion(
            new Region.Id("redistribution"),
            liveness,
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(4));

    assertThat(new CounterexampleFactor().weight(region, transfer)).isCloseTo(3, within(1e-12));
  }

  @Test
  void aRouteHolderWithNoForwardingRouteWeighsTheRemoteExportPolicy() {
    Prefix destination = Prefix.parse("10.200.11.0/24");
    String source =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        router bgp 65001
         neighbor 10.1.0.1 remote-as 65002
         address-family ipv4
          neighbor 10.1.0.1 activate
         exit-address-family
        """;
    String exporter =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        route-map BAD permit 10
         set ip next-hop 203.0.113.1
        router bgp 65002
         neighbor 10.1.0.0 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.0 activate
          neighbor 10.1.0.0 route-map BAD out
         exit-address-family
        """;
    Snapshot snapshot = Snapshot.of(Map.of(R1 + ".cfg", source, R2 + ".cfg", exporter));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0"))));
    Specification target =
        new Specification(new ForwardingPredicate.Reachability(R1, destination), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NULL_ROUTED))),
            Map.of(destination, Set.of(R1, R2)),
            Map.of(destination, Set.of(R2)));
    RepairProblem problem =
        new RepairProblem(
            "unresolved-next-hop", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState unresolved =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    RelationInstance nextHop =
        unresolved.program().instances("route-map-match").stream()
            .filter(instance -> !instance.satisfied())
            .findFirst()
            .orElseThrow();
    Region exact =
        new RelationRegion(
            new Region.Id("unresolved-next-hop"),
            nextHop,
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(4));
    Region unrelated = site(R3);

    CounterexampleFactor factor = new CounterexampleFactor();
    assertThat(factor.weight(exact, unresolved)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(unrelated, unresolved)).isEqualTo(1);
  }

  @Test
  void anUnobservedHostWaypointWeighsItsHostAddressObject() {
    Prefix destination = Prefix.parse("10.0.0.9/32");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg",
                config(1, 2, 65001),
                R2 + ".cfg",
                config(2, 1, 65001) + " bgp router-id 10.0.0.9\n"));
    var program = programs.model(snapshot, state.problem().topology());
    Specification target =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, destination, R2), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(),
            Map.of());
    RepairProblem problem =
        new RepairProblem(
            "missing-waypoint-host",
            snapshot,
            state.problem().topology(),
            SpecificationSet.of(List.of(target)));
    RepairState missing =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            program);
    Region waypointLoopback =
        new SiteRegion(
            new Region.Id("waypoint-loopback"),
            new ProgramObject.Ref("interface", R2, List.of("Loopback0")),
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(2));

    assertThat(new CounterexampleFactor().weight(waypointLoopback, missing))
        .isCloseTo(3, within(1e-12));
  }

  @Test
  void anUnobservedHostWaypointWeighsACollidingHostIdentity() {
    Prefix destination = Prefix.parse("10.0.0.2/32");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg", config(1, 2, 65001),
                R2 + ".cfg", config(1, 1, 65001).replace("hostname r1", "hostname r2")));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0"))));
    var program = programs.model(snapshot, topology);
    Specification target =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, destination, R2), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(),
            Map.of());
    RepairProblem problem =
        new RepairProblem(
            "colliding-waypoint-host", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState missing =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            program);
    RelationInstance collision =
        program.instances("address-identity").stream()
            .filter(instance -> instance.routers().contains(R2))
            .filter(instance -> !instance.satisfied())
            .findFirst()
            .orElseThrow();
    Region exact =
        new RelationRegion(
            new Region.Id("colliding-waypoint-loopback"),
            collision,
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(2));

    assertThat(new CounterexampleFactor().weight(exact, missing)).isGreaterThan(3);
  }

  @Test
  void aForbiddenOriginWeighsAHostIdentityWithoutMissingRoutes() {
    Prefix destination = Prefix.parse("10.0.0.1/32");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg", config(1, 3, 65001),
                R2 + ".cfg", config(1, 3, 65001).replace("hostname r1", "hostname r2"),
                R3 + ".cfg", config(3, 1, 65001)));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R3, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R1, "GigabitEthernet0/1"))));
    var program = programs.model(snapshot, topology);
    Specification forbidden =
        new Specification(
            new ForwardingPredicate.Reachability(R3, destination),
            Specification.Status.parse("broken_added"));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                forbidden.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R3, R1), DataPlaneEvidence.Disposition.ACCEPTED))),
            Map.of(),
            Map.of());
    RepairProblem problem =
        new RepairProblem(
            "forbidden-host-identity", snapshot, topology, SpecificationSet.of(List.of(forbidden)));
    RepairState delivered =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(forbidden), List.of(), List.of()), evidence),
            program);
    RelationInstance collision =
        program.instances("address-identity").stream()
            .filter(instance -> instance.routers().contains(R2))
            .filter(instance -> !instance.satisfied())
            .findFirst()
            .orElseThrow();
    Region region =
        new RelationRegion(
            new Region.Id("colliding-forbidden-loopback"),
            collision,
            List.of(forbidden),
            Map.of(),
            new Region.Hypotheses.Deferred(2));

    // No missing route, so there is no set of origins to reduce the interfaces to
    assertThat(new CounterexampleFactor().weight(region, delivered)).isCloseTo(3, within(1e-12));
  }

  @Test
  void aLeadingDenyAnyReceivesLeaveOneOutDominanceOdds() {
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg",
                """
                hostname r1
                ip access-list extended BAD
                 5 deny ip any any
                 10 permit ip host 10.0.0.1 any
                ip access-list extended ORDINARY
                 10 permit ip host 10.0.0.1 any
                 20 deny ip any any
                """));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    ProgramModel program = programs.model(snapshot, topology);
    RepairProblem problem =
        new RepairProblem(
            "dominated-acl", snapshot, topology, SpecificationSet.of(List.of(BROKEN)));
    RepairState dominated =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(),
                new Verdict(List.of(BROKEN), List.of(), List.of()),
                DataPlaneEvidence.none()),
            program);
    Region bad =
        new SiteRegion(
            new Region.Id("bad"),
            new ProgramObject.Ref("acl", R1, List.of("BAD")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(4));
    Region ordinary =
        new SiteRegion(
            new Region.Id("ordinary"),
            new ProgramObject.Ref("acl", R1, List.of("ORDINARY")),
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(4));

    FirstMatchDominanceFactor factor = new FirstMatchDominanceFactor();
    assertThat(factor.weight(bad, dominated)).isCloseTo(3, within(1e-12));
    assertThat(factor.weight(ordinary, dominated)).isEqualTo(1);
  }

  @Test
  void anUnusedRouteHoldingNextHopIdentifiesItsPathDiversityTransfer() {
    Prefix destination = Prefix.parse("10.200.31.0/24");
    String source =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.2.0.0 255.255.255.254
        """;
    String current =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        router bgp 65001
        """;
    String alternative =
        """
        hostname r3
        interface GigabitEthernet0/0
         ip address 10.2.0.1 255.255.255.254
        router ospf 1
        router bgp 65001
        ip prefix-list ALLOW seq 10 permit 10.200.31.0/24
        route-map TRANSFER permit 10
         match ip address prefix-list ALLOW
        ip prefix-list OTHER seq 10 permit 10.200.99.0/24
        route-map DECOY permit 10
         match ip address prefix-list OTHER
        """;
    Snapshot snapshot =
        Snapshot.of(Map.of(R1 + ".cfg", source, R2 + ".cfg", current, R3 + ".cfg", alternative));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0")),
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/1"),
                    new Topology.Endpoint(R3, "GigabitEthernet0/0"))));
    Specification target =
        new Specification(
            new ForwardingPredicate.LoadBalancing(R1, destination, 2), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1, R2), DataPlaneEvidence.Disposition.ACCEPTED))),
            Map.of(destination, Set.of(R2, R3)),
            Map.of(destination, Set.of(R2, R3)));
    RepairProblem problem =
        new RepairProblem("diversity", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState diversity =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    RelationInstance liveness =
        diversity.program().instances("route-map-liveness").stream()
            .filter(instance -> instance.roles().get("map").key().equals(List.of("TRANSFER")))
            .findFirst()
            .orElseThrow();
    Region region =
        new RelationRegion(
            new Region.Id("alternative-transfer"),
            liveness,
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(4));

    assertThat(new CounterexampleFactor().weight(region, diversity)).isGreaterThan(3);
  }

  @Test
  void aNoRouteFrontierIdentifiesTheExactOutboundPermitList() {
    Prefix destination = Prefix.parse("10.200.0.0/24");
    String source =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        ip prefix-list REFERENCE seq 10 permit 10.200.0.0/24
        router bgp 65001
         neighbor 10.1.0.1 remote-as 65002
        """;
    String holder =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        interface Loopback100
         ip address 10.200.0.1 255.255.255.0
        ip prefix-list EXPORT seq 20 permit 10.200.1.0/24
        route-map OUT deny 100
         match ip address prefix-list EXPORT
         set metric 9
        router bgp 65002
         neighbor 10.1.0.0 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.0 activate
          neighbor 10.1.0.0 route-map OUT out
         exit-address-family
        """;
    Snapshot snapshot = Snapshot.of(Map.of(R1 + ".cfg", source, R2 + ".cfg", holder));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0"))));
    Specification target =
        new Specification(new ForwardingPredicate.Reachability(R1, destination), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(destination, Set.of(R2)),
            Map.of(destination, Set.of(R2)));
    RepairProblem problem =
        new RepairProblem("policy-cut", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState cut =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    ProgramObject.Ref exact = new ProgramObject.Ref("prefix-list", R2, List.of("EXPORT"));
    Region exactRegion =
        new SiteRegion(
            new Region.Id("exact-cut"),
            exact,
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(3));
    Region mereReference =
        new SiteRegion(
            new Region.Id("reference"),
            new ProgramObject.Ref("prefix-list", R1, List.of("REFERENCE")),
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(3));

    assertThat(PolicyCut.missingPermits(cut)).containsOnlyKeys(exact);
    assertThat(new PolicyCutFactor().weight(exactRegion, cut)).isEqualTo(6);
    assertThat(new PolicyCutFactor().weight(mereReference, cut)).isEqualTo(1);
  }

  @Test
  void aNoRouteFrontierIdentifiesAnInertTransformingDenyClause() {
    Prefix destination = Prefix.parse("10.200.0.0/24");
    String source =
        """
        hostname r1
        interface GigabitEthernet0/0
         ip address 10.1.0.0 255.255.255.254
        router bgp 65001
         neighbor 10.1.0.1 remote-as 65002
        """;
    String holder =
        """
        hostname r2
        interface GigabitEthernet0/0
         ip address 10.1.0.1 255.255.255.254
        interface Loopback100
         ip address 10.200.0.1 255.255.255.0
        ip prefix-list EXPORT seq 10 permit 10.200.0.0/24
        route-map OUT deny 100
         match ip address prefix-list EXPORT
         set metric 9
        router bgp 65002
         neighbor 10.1.0.0 remote-as 65001
         address-family ipv4
          neighbor 10.1.0.0 activate
          neighbor 10.1.0.0 route-map OUT out
         exit-address-family
        """;
    Snapshot snapshot = Snapshot.of(Map.of(R1 + ".cfg", source, R2 + ".cfg", holder));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(R1, "GigabitEthernet0/0"),
                    new Topology.Endpoint(R2, "GigabitEthernet0/0"))));
    Specification target =
        new Specification(new ForwardingPredicate.Reachability(R1, destination), BROKEN.status());
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(destination, Set.of(R2)),
            Map.of(destination, Set.of(R2)));
    RepairProblem problem =
        new RepairProblem("policy-gate", snapshot, topology, SpecificationSet.of(List.of(target)));
    RepairState cut =
        RepairState.initial(
            problem,
            new Verification(
                snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
            programs.model(snapshot, topology));
    ProgramObject.Ref gate = new ProgramObject.Ref("route-map-entry", R2, List.of("OUT", "100"));
    Region gateRegion =
        new SiteRegion(
            new Region.Id("exact-gate"),
            gate,
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(1));

    assertThat(PolicyCut.missingPermits(cut)).containsOnlyKeys(gate);
    assertThat(new PolicyCutFactor().weight(gateRegion, cut)).isEqualTo(3);
  }

  @Test
  void experienceLearnsFromVerifiedClassesWhileTheirRoutersAreUnchanged() {
    TransitionLedger ledger = new TransitionLedger();
    ExperienceFactor factor = new ExperienceFactor(ledger);
    Hypothesis fix = hypothesis(R3, "65001");
    Hypothesis sibling = hypothesis(R3, "65002");
    Region region = site(R3).withHypotheses(new Region.Hypotheses.Admitted(List.of(sibling)));
    assertThat(factor.weight(region, state)).isEqualTo(1);
    var dependency = HypothesisDependency.of(fix, state);
    ledger.add(
        new TransitionLedger.Transition(
            0, region.id(), fix, state.verification(), Verdict.Change.NO_EFFECT, dependency));
    assertThat(factor.weight(region, state)).isCloseTo(1.0 / 3, within(1e-12));
    RepairState changedEvidence =
        RepairState.initial(
            state.problem(),
            new Verification(
                state.snapshot().id(), state.verification().verdict(), DataPlaneEvidence.none()),
            state.program());
    assertThat(factor.weight(region, changedEvidence)).isEqualTo(1);
    ledger.add(
        new TransitionLedger.Transition(
            0, region.id(), fix, state.verification(), Verdict.Change.IMPROVEMENT, dependency));
    assertThat(factor.weight(region, state)).isCloseTo(1, within(1e-12));
    ledger.add(
        new TransitionLedger.Transition(
            0, region.id(), fix, state.verification(), Verdict.Change.MIXED, dependency));
    assertThat(factor.weight(region, state)).isCloseTo(1, within(1e-12));
  }

  @Test
  void experienceTransfersFailuresToDeferredRegionsOfTheSameRelationClass() {
    TransitionLedger ledger = new TransitionLedger();
    ExperienceFactor factor = new ExperienceFactor(ledger);
    RelationInstance instance =
        state.program().violated().stream()
            .filter(candidate -> candidate.relation().equals("bgp-session"))
            .findFirst()
            .orElseThrow();
    var requirement = instance.violated().getFirst();
    Region deferred =
        new RelationRegion(
            new Region.Id("deferred"),
            instance,
            List.of(BROKEN),
            Map.of(),
            new Region.Hypotheses.Deferred(4));
    Hypothesis failed =
        Hypothesis.of(
            state.snapshot(),
            List.of(
                new EditOperator.ReplaceSlots(
                    state.program().statements(R3).getLast(),
                    "asn",
                    List.of("65001"),
                    new Provenance.Forced("bgp-session", requirement))));

    ledger.add(
        new TransitionLedger.Transition(
            0,
            deferred.id(),
            failed,
            state.verification(),
            Verdict.Change.NO_EFFECT,
            HypothesisDependency.of(failed, state)));

    assertThat(factor.weight(deferred, state)).isCloseTo(1.0 / 3, within(1e-12));
  }

  private static Hypothesis hypothesis(RouterId router, String asn) {
    return Hypothesis.of(
        state.snapshot(),
        List.of(
            new EditOperator.ReplaceSlots(
                state.program().statements(router).getLast(),
                "asn",
                List.of(asn),
                new Provenance.Existing())));
  }
}
