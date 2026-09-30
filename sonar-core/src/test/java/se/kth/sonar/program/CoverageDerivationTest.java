package se.kth.sonar.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.text.RouterId;

class CoverageDerivationTest {
  private static final String R1 =
      """
      hostname r1
      interface Loopback0
       ip address 10.0.0.1 255.255.255.255
      interface GigabitEthernet0/0
       ip address 10.1.0.4 255.255.255.254
      interface GigabitEthernet0/2
       ip address 10.1.0.8 255.255.255.254
      router ospf 1
       router-id 10.0.0.1
       network 10.0.0.1 0.0.0.0 area 0
       network 10.1.0.4 0.0.0.1 area 0
       network 10.1.0.8 0.0.0.1 area 0
      """;

  @Test
  void objectsKeepTheirSpansWhenLinesShiftAroundThem() {
    Dialect dialect = Dialect.load("cisco-ios");
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      ProgramService programs =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      Snapshot before = Snapshot.of(Map.of("r1.cfg", R1));
      Topology topology = Topology.of(before.routers(), List.of());
      ProgramModel earlier = programs.model(before, topology);
      Snapshot after =
          Snapshot.of(
              Map.of("r1.cfg", "hostname r1\n!\n" + R1.substring("hostname r1\n".length())));
      ProgramModel later = programs.model(after, topology, java.util.Optional.of(earlier));
      ProgramObject.Ref network =
          new ProgramObject.Ref("ospf-network", RouterId.of("r1"), List.of("10.0.0.1", "0.0.0.0"));
      assertThat(later.object(network).orElseThrow().statements().getFirst().first())
          .isEqualTo(earlier.object(network).orElseThrow().statements().getFirst().first() + 1);
      assertThat(later.object(network).orElseThrow().attributes())
          .isEqualTo(earlier.object(network).orElseThrow().attributes());
    }
  }

  @Test
  void networkStatementsKnowTheInterfaceTheyCoverAndRoutersTheirLoopback() {
    Dialect dialect = Dialect.load("cisco-ios");
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      ProgramService programs =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      Snapshot snapshot = Snapshot.of(Map.of("r1.cfg", R1));
      ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
      RouterId r1 = RouterId.of("r1");
      ProgramObject network =
          program
              .object(new ProgramObject.Ref("ospf-network", r1, List.of("10.0.0.1", "0.0.0.0")))
              .orElseThrow();
      assertThat(network.value("covered")).hasValue("interface:r1:Loopback0");
      assertThat(
              program
                  .object(new ProgramObject.Ref("ospf-network", r1, List.of("10.1.0.4", "0.0.0.1")))
                  .orElseThrow()
                  .value("covered"))
          .hasValue("interface:r1:GigabitEthernet0/0");
      ProgramObject router =
          program.object(new ProgramObject.Ref("router", r1, List.of())).orElseThrow();
      assertThat(router.value("loopback-address")).hasValue("10.0.0.1");
      assertThat(program.instances("ospf-coverage")).hasSize(3);
      assertThat(program.instances("ospf-coverage").getFirst().satisfied()).isTrue();
    }
  }
}
