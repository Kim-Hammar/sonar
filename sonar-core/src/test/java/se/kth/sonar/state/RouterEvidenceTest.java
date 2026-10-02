package se.kth.sonar.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

/** Three routers in a line: r1 has no route to a prefix that r2 and r3 hold in BGP. */
class RouterEvidenceTest {
  private static final RouterId R1 = RouterId.of("1_as65001_r1");
  private static final RouterId R2 = RouterId.of("2_as65001_r2");
  private static final RouterId R3 = RouterId.of("3_as65001_r3");
  private static final Prefix DESTINATION = Prefix.parse("10.200.27.0/24");

  private static String router(int n, String bgp) {
    return """
        hostname r%d
        interface Loopback0
         ip address 10.0.0.%d 255.255.255.255
        interface GigabitEthernet0/0
         ip address 10.1.%d.1 255.255.255.254
        interface GigabitEthernet0/1
         ip address 10.2.%d.1 255.255.255.254
        %s
        """
        .formatted(n, n, n, n, bgp);
  }

  private static RepairState state(String middle, String last) {
    Dialect dialect = Dialect.load("cisco-ios");
    Snapshot snapshot =
        Snapshot.of(
            Map.of(
                R1 + ".cfg",
                router(1, ""),
                R2 + ".cfg",
                router(2, middle),
                R3 + ".cfg",
                router(3, last)));
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
    Specification target =
        new Specification(
            new ForwardingPredicate.Reachability(R1, DESTINATION),
            Specification.Status.parse("broken_removed"));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                target.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(DESTINATION, Set.of(R2, R3)),
            Map.of(DESTINATION, Set.of(R2, R3)));
    RepairProblem problem =
        new RepairProblem("line", snapshot, topology, SpecificationSet.of(List.of(target)));
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      return RepairState.initial(
          problem,
          new Verification(
              snapshot.id(), new Verdict(List.of(target), List.of(), List.of()), evidence),
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context)
              .model(snapshot, topology));
    }
  }

  private static final String ORIGIN =
      """
      router bgp 65001
       neighbor 10.0.0.2 remote-as 65001
       address-family ipv4
        network 10.200.27.0 mask 255.255.255.0
       exit-address-family
      """;

  @Test
  void aTransferBoundaryLiesAtTheNearestHolder() {
    RepairState unpeered = state("router bgp 65001", ORIGIN);
    assertThat(RouterEvidence.of(unpeered).transferBoundary(R1, DESTINATION)).containsExactly(R2);
  }

  @Test
  void aTransferBoundaryPassesOverAHolderSuppliedOnlyFromInsideItsAutonomousSystem() {
    RepairState internal =
        state(
            """
            router bgp 65001
             neighbor 10.0.0.3 remote-as 65001
            """,
            ORIGIN);
    assertThat(RouterEvidence.of(internal).transferBoundary(R1, DESTINATION)).containsExactly(R3);
    assertThat(RouterEvidence.of(internal).routeFrontier(R1, DESTINATION))
        .containsExactly(RouterEvidence.Link.of(R1, R2));
  }

  @Test
  void aTransferBoundaryKeepsAHolderWithAnExternalSupplier() {
    RepairState external =
        state(
            """
            router bgp 65002
             neighbor 10.0.0.3 remote-as 65001
            """,
            ORIGIN);
    assertThat(RouterEvidence.of(external).transferBoundary(R1, DESTINATION)).containsExactly(R2);
  }
}
