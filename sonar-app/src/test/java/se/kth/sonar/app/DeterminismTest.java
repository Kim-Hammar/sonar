package se.kth.sonar.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.trace.TraceRecord;

class DeterminismTest {
  @TempDir Path dataset;
  @TempDir Path output;

  private Map<String, List<String>> projections(List<String> scenarios, int scenarioParallelism)
      throws IOException {
    RunSettings settings =
        new RunSettings(
            10,
            new ParallelismConfig(1, 1, 1, scenarioParallelism, 0),
            Map.of(),
            Optional.empty(),
            11);
    List<TraceRecord> records = new CopyOnWriteArrayList<>();
    try (Pipeline pipeline =
        Pipeline.open(
            new Workspace(dataset, output), settings, new Pipeline.Agent.Scripted(Duration.ZERO))) {
      pipeline.run(scenarios, new Cancellation(), records::add);
    }
    Map<String, List<String>> projections = new LinkedHashMap<>();
    Map<String, TraceJson> encoders = new LinkedHashMap<>();
    Map<String, Long> steps = new LinkedHashMap<>();
    for (TraceRecord record : records) {
      TraceJson json = encoders.computeIfAbsent(record.scenario(), scenario -> new TraceJson());
      long step = steps.merge(record.scenario(), 1L, Long::sum);
      TraceJson.Encoded encoded = json.encode(0, step, record);
      projections
          .computeIfAbsent(record.scenario(), scenario -> new ArrayList<>())
          .add(strip(encoded.summary()) + strip(encoded.detail()));
    }
    return projections;
  }

  private static String strip(String line) throws IOException {
    JsonNode node = Json.mapper().readTree(line);
    ((ObjectNode) node).remove("at");
    return node.toString();
  }

  @Test
  void parallelRunsProduceTheSameScenarioTracesAsSequentialRuns() throws IOException {
    List<String> scenarios = TestDataset.create(dataset, 3);
    Map<String, List<String>> sequential = projections(scenarios, 1);
    Map<String, List<String>> parallel = projections(scenarios, 3);
    assertThat(sequential.keySet()).containsExactlyInAnyOrderElementsOf(scenarios);
    for (String scenario : scenarios) {
      assertThat(parallel.get(scenario)).isEqualTo(sequential.get(scenario));
      assertThat(sequential.get(scenario)).hasSizeGreaterThan(3);
    }
  }
}
