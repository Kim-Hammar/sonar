package se.kth.sonar.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceRecord;

class BenchmarkRunnerTest {
  private static final List<String> SCENARIOS = List.of("s1", "s2", "s3", "s4");

  private static RepairProblem problem(String name) {
    Snapshot snapshot = Snapshot.of(Map.of(name + ".cfg", "hostname " + name + "\n"));
    return new RepairProblem(
        name, snapshot, Topology.of(snapshot.routers(), List.of()), SpecificationSet.of(List.of()));
  }

  private static RepairResult solved(RepairProblem problem) {
    return new RepairResult(
        problem.name(),
        problem.initial(),
        new Verdict(List.of(), List.of(), List.of()),
        List.of(),
        new RepairResult.Stop.Solved(),
        Map.of());
  }

  private static ExecutionContext context(int scenarioParallelism) {
    return ExecutionContext.open(
        new ParallelismConfig(1, 1, 1, scenarioParallelism, 0), List.of(RunScope.CURRENT));
  }

  @Test
  void outcomesKeepScenarioOrderAndEachScenarioHasItsOwnScope() {
    Set<Budget> budgets = ConcurrentHashMap.newKeySet();
    Repairer repairer =
        problem -> {
          RunScope scope = RunScope.current().orElseThrow();
          assertThat(scope.scenario()).isEqualTo(problem.name());
          assertThat(scope.seed()).isEqualTo(7L);
          budgets.add(scope.budget());
          return solved(problem);
        };
    try (ExecutionContext context = context(4)) {
      List<BenchmarkRunner.Outcome> outcomes =
          new BenchmarkRunner(repairer, context)
              .run(
                  SCENARIOS,
                  BenchmarkRunnerTest::problem,
                  Budget::unlimited,
                  7L,
                  new Cancellation(),
                  record -> {});
      assertThat(outcomes)
          .extracting(BenchmarkRunner.Outcome::scenario)
          .containsExactlyElementsOf(SCENARIOS);
      assertThat(outcomes)
          .allMatch(outcome -> outcome instanceof BenchmarkRunner.Outcome.Completed);
    }
    assertThat(budgets).hasSize(4);
  }

  @Test
  void aFailingScenarioDoesNotStopTheOthers() {
    Repairer repairer =
        problem -> {
          if (problem.name().equals("s2")) {
            throw new NotImplemented("RepairEngine.repair");
          }
          return solved(problem);
        };
    try (ExecutionContext context = context(2)) {
      List<BenchmarkRunner.Outcome> outcomes =
          new BenchmarkRunner(repairer, context)
              .run(
                  SCENARIOS,
                  name -> {
                    if (name.equals("s3")) {
                      throw new IllegalArgumentException("no scenario s3");
                    }
                    return problem(name);
                  },
                  Budget::unlimited,
                  0L,
                  new Cancellation(),
                  record -> {});
      assertThat(outcomes.get(0)).isInstanceOf(BenchmarkRunner.Outcome.Completed.class);
      assertThat(outcomes.get(1))
          .isEqualTo(
              new BenchmarkRunner.Outcome.Failed(
                  "s2", "RepairEngine.repair is not implemented yet"));
      assertThat(outcomes.get(2))
          .isEqualTo(new BenchmarkRunner.Outcome.Failed("s3", "no scenario s3"));
      assertThat(outcomes.get(3)).isInstanceOf(BenchmarkRunner.Outcome.Completed.class);
    }
  }

  @Test
  void eachScenarioReportsItsLoadAndItsEnd() {
    List<TraceRecord> records = new CopyOnWriteArrayList<>();
    Repairer repairer =
        problem -> {
          RunScope.current().orElseThrow().budget().charge(Budget.Resource.VERIFICATIONS, 3);
          if (problem.name().equals("s2")) {
            throw new NotImplemented("RepairEngine.repair");
          }
          return solved(problem);
        };
    try (ExecutionContext context = context(3)) {
      new BenchmarkRunner(repairer, context)
          .run(
              SCENARIOS,
              BenchmarkRunnerTest::problem,
              Budget::unlimited,
              0L,
              new Cancellation(),
              records::add);
    }
    for (String scenario : SCENARIOS) {
      List<TraceRecord> own =
          records.stream().filter(record -> record.scenario().equals(scenario)).toList();
      assertThat(own).hasSize(2);
      assertThat(own.get(0).event()).isInstanceOf(TraceEvent.Loaded.class);
      assertThat(own.get(1).event())
          .isInstanceOf(
              scenario.equals("s2") ? TraceEvent.Failed.class : TraceEvent.Finished.class);
      assertThat(own.get(1).usage()).containsEntry(Budget.Resource.VERIFICATIONS, 3L);
      assertThat(own.get(0).usage()).containsEntry(Budget.Resource.VERIFICATIONS, 0L);
    }
  }

  @Test
  void cancellationSkipsScenariosThatHaveNotStarted() {
    Cancellation cancellation = new Cancellation();
    Repairer repairer =
        problem -> {
          if (problem.name().equals("s2")) {
            cancellation.cancel();
          }
          return solved(problem);
        };
    try (ExecutionContext context = context(1)) {
      List<BenchmarkRunner.Outcome> outcomes =
          new BenchmarkRunner(repairer, context)
              .run(
                  SCENARIOS,
                  BenchmarkRunnerTest::problem,
                  Budget::unlimited,
                  0L,
                  cancellation,
                  record -> {});
      assertThat(outcomes.subList(0, 2))
          .allMatch(outcome -> outcome instanceof BenchmarkRunner.Outcome.Completed);
      assertThat(outcomes.subList(2, 4))
          .containsExactly(
              new BenchmarkRunner.Outcome.Cancelled("s3"),
              new BenchmarkRunner.Outcome.Cancelled("s4"));
    }
  }

  @Test
  void aRepairInterruptedByCancellationIsReportedAsCancelled() {
    Cancellation cancellation = new Cancellation();
    List<TraceRecord> records = new CopyOnWriteArrayList<>();
    Repairer repairer =
        problem -> {
          cancellation.cancel();
          cancellation.checkNotCancelled();
          return solved(problem);
        };
    try (ExecutionContext context = context(1)) {
      List<BenchmarkRunner.Outcome> outcomes =
          new BenchmarkRunner(repairer, context)
              .run(
                  List.of("s1"),
                  BenchmarkRunnerTest::problem,
                  Budget::unlimited,
                  0L,
                  cancellation,
                  records::add);
      assertThat(outcomes).containsExactly(new BenchmarkRunner.Outcome.Cancelled("s1"));
    }
    assertThat(records.getLast().event()).isEqualTo(new TraceEvent.Failed("the run was cancelled"));
  }
}
