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
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
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
  void experienceLearnsFromVerifiedClassesWhileTheirRoutersAreUnchanged() {
    TransitionLedger ledger = new TransitionLedger();
    ExperienceFactor factor = new ExperienceFactor(ledger);
    Hypothesis fix = hypothesis(R3, "65001");
    Hypothesis sibling = hypothesis(R3, "65002");
    Region region = site(R3).withHypotheses(new Region.Hypotheses.Admitted(List.of(sibling)));
    assertThat(factor.weight(region, state)).isEqualTo(1);
    ledger.add(
        new TransitionLedger.Transition(
            0, region.id(), fix, state.verification(), Verdict.Change.IMPROVEMENT));
    assertThat(factor.weight(region, state)).isCloseTo(3, within(1e-12));
    ledger.add(
        new TransitionLedger.Transition(
            0, region.id(), fix, state.verification(), Verdict.Change.NO_EFFECT));
    assertThat(factor.weight(region, state)).isCloseTo(1, within(1e-12));
    ledger.add(
        new TransitionLedger.Transition(
            0, region.id(), fix, state.verification(), Verdict.Change.MIXED));
    assertThat(factor.weight(region, state)).isCloseTo(1, within(1e-12));
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
