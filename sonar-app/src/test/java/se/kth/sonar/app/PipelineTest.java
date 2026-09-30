package se.kth.sonar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.engine.BenchmarkRunner;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceRecord;

class PipelineTest {
  @TempDir Path dataset;
  @TempDir Path output;

  private static RunSettings settings(Map<Budget.Resource, Long> limits) {
    return new RunSettings(10, ParallelismConfig.sequential(), limits, Optional.empty(), 3);
  }

  @Test
  void theEngineSearchesWithinItsBudget() {
    TestDataset.create(dataset, 1);
    List<TraceRecord> records = new CopyOnWriteArrayList<>();
    Pipeline pipeline =
        Pipeline.open(
            new Workspace(dataset, output),
            settings(Map.of(Budget.Resource.VERIFICATIONS, 3L, Budget.Resource.DECISIONS, 0L)),
            new Pipeline.Agent.Engine(HttpTransport.Settings.openRouter()));
    try (pipeline) {
      assertThat(pipeline.scenarios().scenarios()).containsExactly("scenario-001");
      List<BenchmarkRunner.Outcome> outcomes =
          pipeline.run(List.of("scenario-001"), new Cancellation(), records::add);
      assertThat(outcomes).hasSize(1);
      assertThat(outcomes.getFirst()).isInstanceOf(BenchmarkRunner.Outcome.Completed.class);
      RepairResult result = ((BenchmarkRunner.Outcome.Completed) outcomes.getFirst()).result();
      assertThat(result.stop())
          .isEqualTo(new RepairResult.Stop.BudgetExhausted(Budget.Resource.VERIFICATIONS));
      assertThat(result.usage()).containsEntry(Budget.Resource.VERIFICATIONS, 3L);
    }
    assertThat(records)
        .extracting(record -> record.event().getClass().getSimpleName())
        .startsWith("Loaded", "Baseline", "SpaceBuilt")
        .contains("Tried")
        .endsWith("Finished");
    pipeline.close();
    assertThatThrownBy(
            () -> pipeline.run(List.of("scenario-001"), new Cancellation(), record -> {}))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theScriptedAgentStopsWhenSolvedOrOutOfBudget() {
    TestDataset.create(dataset, 2);
    List<TraceRecord> records = new CopyOnWriteArrayList<>();
    try (Pipeline pipeline =
        Pipeline.open(
            new Workspace(dataset, output),
            settings(Map.of(Budget.Resource.VERIFICATIONS, 6L)),
            new Pipeline.Agent.Scripted(Duration.ZERO))) {
      List<BenchmarkRunner.Outcome> outcomes =
          pipeline.run(List.of("scenario-001", "scenario-002"), new Cancellation(), records::add);
      assertThat(outcomes)
          .allSatisfy(
              outcome -> {
                assertThat(outcome).isInstanceOf(BenchmarkRunner.Outcome.Completed.class);
                RepairResult.Stop stop =
                    ((BenchmarkRunner.Outcome.Completed) outcome).result().stop();
                assertThat(stop)
                    .isIn(
                        new RepairResult.Stop.Solved(),
                        new RepairResult.Stop.BudgetExhausted(Budget.Resource.VERIFICATIONS));
              });
    }
    assertThat(records)
        .extracting(TraceRecord::event)
        .hasAtLeastOneElementOfType(TraceEvent.Decided.class);
    assertThat(records)
        .extracting(TraceRecord::event)
        .hasAtLeastOneElementOfType(TraceEvent.Tried.class);
    assertThat(
            records.stream()
                .filter(record -> record.event() instanceof TraceEvent.Finished)
                .count())
        .isEqualTo(2);
  }
}
