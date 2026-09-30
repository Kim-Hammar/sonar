package se.kth.sonar.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.text.RouterId;

class RouteMapEvaluationTest {
  @Test
  void evaluatesPrefixListsInClauseOrderAndSkipsNonPrefixClauses() {
    String config =
        """
        hostname r1
        ip prefix-list DENY seq 10 permit 10.180.0.0/16 le 32
        ip prefix-list ALLOW seq 10 permit 10.200.0.0/16 le 24
        route-map EXPORT deny 5
         match tag 65100
        route-map EXPORT deny 10
         match ip address prefix-list DENY
        route-map EXPORT permit 20
         match ip address prefix-list ALLOW
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", config));
    Dialect dialect = Dialect.load("cisco-ios");
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      ProgramModel program =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context)
              .model(snapshot, Topology.of(snapshot.routers(), List.of()));
      ProgramObject.Ref map =
          new ProgramObject.Ref("route-map", RouterId.of("1_as65001_r1"), List.of("EXPORT"));

      assertThat(RouteMapEvaluation.permits(program, map, Prefix.parse("10.200.27.0/24"))).isTrue();
      assertThat(RouteMapEvaluation.permits(program, map, Prefix.parse("10.180.1.0/24"))).isFalse();
      assertThat(RouteMapEvaluation.permits(program, map, Prefix.parse("192.0.2.0/24"))).isFalse();
      assertThat(
              RouteMapEvaluation.possibleBlockers(program, map, Prefix.parse("10.200.27.0/24"))
                  .stream()
                  .map(object -> object.ref().key().getLast()))
          .containsExactly("5");
      assertThat(RouteMapEvaluation.possibleBlockers(program, map, Prefix.parse("10.180.1.0/24")))
          .extracting(object -> object.ref().key().getLast())
          .containsExactly("5", "10");
    }
  }
}
