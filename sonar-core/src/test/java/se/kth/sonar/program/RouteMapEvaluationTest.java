package se.kth.sonar.program;

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
      assertThat(RouteMapEvaluation.decidingClauses(program, map, Prefix.parse("10.200.27.0/24")))
          .extracting(object -> object.ref().key().getLast())
          .containsExactly("5", "20");
      assertThat(RouteMapEvaluation.decidingClauses(program, map, Prefix.parse("192.0.2.0/24")))
          .extracting(object -> object.ref().key().getLast())
          .containsExactly("5");
    }
  }

  @Test
  void provesOnlyPrefixDeterminedDenials() {
    String config =
        """
        hostname r1
        ip prefix-list BLOCK seq 10 permit 10.180.0.0/16 le 32
        route-map CERTAIN deny 10
         match ip address prefix-list BLOCK
        route-map CERTAIN permit 20
        route-map CONDITIONAL permit 10
         match tag 65100
        route-map CONDITIONAL deny 20
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", config));
    Dialect dialect = Dialect.load("cisco-ios");
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      ProgramModel program =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context)
              .model(snapshot, Topology.of(snapshot.routers(), List.of()));
      RouterId router = RouterId.of("1_as65001_r1");
      ProgramObject.Ref certain = new ProgramObject.Ref("route-map", router, List.of("CERTAIN"));
      ProgramObject.Ref conditional =
          new ProgramObject.Ref("route-map", router, List.of("CONDITIONAL"));

      assertThat(
              RouteMapEvaluation.definitelyDenies(program, certain, Prefix.parse("10.180.1.0/24")))
          .isTrue();
      assertThat(
              RouteMapEvaluation.definitelyDenies(program, certain, Prefix.parse("10.200.0.0/24")))
          .isFalse();
      assertThat(
              RouteMapEvaluation.definitelyDenies(
                  program, conditional, Prefix.parse("10.180.1.0/24")))
          .isFalse();
    }
  }

  @Test
  void identifiesOnlyPrefixListsWhoseCompletionOpensTheRouteMap() {
    String config =
        """
        hostname r1
        ip prefix-list BLOCK seq 10 permit 10.180.0.0/16 le 32
        ip prefix-list EXPORT seq 20 permit 10.200.1.0/24
        ip prefix-list SHADOWED seq 20 permit 10.200.1.0/24
        route-map EXPORT deny 10
         match ip address prefix-list BLOCK
        route-map EXPORT permit 20
         match ip address prefix-list EXPORT
        route-map SHADOWED deny 10
        route-map SHADOWED permit 20
         match ip address prefix-list SHADOWED
        """;
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", config));
    Dialect dialect = Dialect.load("cisco-ios");
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      ProgramModel program =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context)
              .model(snapshot, Topology.of(snapshot.routers(), List.of()));
      RouterId router = RouterId.of("1_as65001_r1");
      Prefix missing = Prefix.parse("10.200.0.0/24");
      ProgramObject.Ref export = new ProgramObject.Ref("route-map", router, List.of("EXPORT"));
      ProgramObject.Ref shadowed = new ProgramObject.Ref("route-map", router, List.of("SHADOWED"));

      assertThat(RouteMapEvaluation.completionLists(program, export, Set.of(missing)))
          .containsExactly(new ProgramObject.Ref("prefix-list", router, List.of("EXPORT")));
      assertThat(RouteMapEvaluation.completionLists(program, shadowed, Set.of(missing))).isEmpty();
    }
  }
}
