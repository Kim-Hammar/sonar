package se.kth.sonar.prior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
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
    assertThat(r3).isGreaterThan(r1).isGreaterThan(r2);
    assertThat(r3).isCloseTo(Math.sqrt(2.0 / 3 * 2.0 / 3), within(1e-12));
    assertThat(r1).isCloseTo(Math.sqrt(2.0 / 4 * 2.0 / 3), within(1e-12));
    assertThat(r2).isCloseTo(Math.sqrt(1.0 / 3 * 1.0 / 3), within(1e-12));
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
    assertThat(factor.weight(spanning, state)).isCloseTo(3, within(1e-12));
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
        route-map OUT permit 100
         match ip address prefix-list EXPORT
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
    assertThat(new PolicyCutFactor().weight(exactRegion, cut)).isEqualTo(3);
    assertThat(new PolicyCutFactor().weight(mereReference, cut)).isEqualTo(1);
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
