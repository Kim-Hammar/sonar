package se.kth.sonar.relation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;

class AbsentConstraintTest {
  private static final String R1 =
      """
      hostname r1
      interface Loopback0
       ip address 10.0.0.1 255.255.255.255
      router bgp 65001
       neighbor 10.0.0.2 remote-as 65001
       neighbor 10.0.0.2 shutdown
       neighbor 10.0.0.3 remote-as 65001
      """;

  @Test
  void aShutNeighborViolatesTheAbsenceRequirementAndAnOpenOneDoesNot() {
    Dialect dialect = Dialect.load("cisco-ios");
    Requirement absent =
        dialect.schema().relation("bgp-activation").orElseThrow().requirements().stream()
            .filter(r -> r.constraint() instanceof Constraint.Absent)
            .findFirst()
            .orElseThrow();
    assertThat(absent.text()).isEqualTo("soft absent neighbor.shutdown");
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      ProgramService programs =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      Snapshot snapshot = Snapshot.of(Map.of("r1.cfg", R1));
      ProgramModel program = programs.model(snapshot, Topology.of(snapshot.routers(), List.of()));
      List<RelationInstance> activations = program.instances("bgp-activation");
      assertThat(activations).hasSize(2);
      assertThat(activations.get(0).violated()).contains(absent);
      assertThat(activations.get(1).violated()).doesNotContain(absent);
    }
  }
}
