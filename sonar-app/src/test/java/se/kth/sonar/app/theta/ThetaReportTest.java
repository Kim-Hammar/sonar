package se.kth.sonar.app.theta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.Json;

class ThetaReportTest {
  @TempDir Path directory;

  private Path run(String name, int rank) throws IOException {
    Path run = Files.createDirectories(directory.resolve(name));
    Files.write(
        run.resolve("events.jsonl"),
        List.of(
            "{\"seq\":1,\"scenario\":\"s\"}",
            "{\"seq\":2,\"scenario\":\"s\"}",
            "{\"seq\":3,\"scenario\":\"s\"}"));
    Files.write(
        run.resolve("details.jsonl"),
        List.of(
            "{\"seq\":1,\"type\":\"decided\",\"question\":\"hypothesis\",\"outcome\":\"answered\","
                + "\"answer\":{\"order\":[{\"id\":\"a\"},{\"id\":\"b\"}]}}",
            "{\"seq\":2,\"type\":\"tried\",\"hypothesis\":{\"id\":\"a\"},\"change\":\""
                + (rank == 1 ? "improvement" : "no-effect")
                + "\"}",
            "{\"seq\":3,\"type\":\"tried\",\"hypothesis\":{\"id\":\"b\"},\"change\":\"improvement\"}"));
    return run;
  }

  @Test
  void writesTheEstimatesTheObservationsAndASelfContainedFigure() throws IOException {
    Map<String, Path> runs = new LinkedHashMap<>();
    runs.put("Model A", run("a", 1));
    runs.put("Model B", run("b", 2));
    Path output = directory.resolve("report");
    List<ThetaFigure.Series> series = ThetaReport.write(runs, output, 3);

    assertThat(series).extracting(ThetaFigure.Series::model).containsExactly("Model A", "Model B");
    JsonNode models = Json.mapper().readTree(output.resolve("theta.json").toFile());
    assertThat(models).hasSize(2);
    assertThat(models.get(1).path("episodes").asInt()).isEqualTo(1);
    assertThat(Files.readAllLines(output.resolve("observations.csv")))
        .containsExactly("model,scenario,rank", "Model A,s,1", "Model B,s,2");
    String tex = Files.readString(output.resolve("theta.tex"));
    assertThat(tex).contains("\\pgfplotstableread", "Model A", "Model B", "1-exp(-");
    assertThat(tex).doesNotContain(".dat", ".csv");
  }

  @Test
  void refusesAModelWithoutObservations() throws IOException {
    Path empty = Files.createDirectories(directory.resolve("empty"));
    Files.write(empty.resolve("events.jsonl"), List.of());
    Files.write(empty.resolve("details.jsonl"), List.of());
    assertThatThrownBy(() -> ThetaReport.write(Map.of("Empty", empty), directory.resolve("out"), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Empty");
  }
}
