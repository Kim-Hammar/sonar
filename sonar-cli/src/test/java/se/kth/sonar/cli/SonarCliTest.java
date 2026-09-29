package se.kth.sonar.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.TestDataset;

class SonarCliTest {
  private record Result(int status, String out, String err) {}

  private static Result run(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int status = SonarCli.execute(new PrintWriter(out, true), new PrintWriter(err, true), args);
    return new Result(status, out.toString(), err.toString());
  }

  @Test
  void versionNamesTheProjectAndTheEmbeddedBatfish() {
    Result result = run("--version");
    assertThat(result.status()).isZero();
    assertThat(result.out())
        .contains("SONAR: Search-Optimal Network Agentic Repair")
        .contains("Batfish 0.36.0");
  }

  @Test
  void helpListsTheCommands() {
    Result result = run("--help");
    assertThat(result.status()).isZero();
    assertThat(result.out()).contains("repair").contains("serve");
    assertThat(run().out()).contains("Usage: sonar");
    assertThat(run("serve", "--help").out())
        .contains("--port")
        .contains("--demo")
        .contains("default: dataset/main_dataset")
        .contains("default: output");
  }

  @Test
  void repairRecordsARunThatStopsAtTheEnginePlaceholder(@TempDir Path dataset, @TempDir Path output)
      throws IOException {
    TestDataset.create(dataset, 1);
    Result result =
        run(
            "repair",
            "--dataset",
            dataset.toString(),
            "--scenario",
            "scenario-001",
            "--output",
            output.toString(),
            "--max-verifications",
            "10");
    assertThat(result.status()).isEqualTo(1);
    assertThat(result.out())
        .contains("scenario-001  failed: RepairEngine.repair is not implemented yet");
    try (Stream<Path> runs = Files.list(output.resolve("runs"))) {
      Path run = runs.findFirst().orElseThrow();
      assertThat(Files.readString(run.resolve("run.json")))
          .contains("\"status\":\"finished\"")
          .contains("\"verifications\":10");
      assertThat(Files.readAllLines(run.resolve("events.jsonl"))).hasSize(2);
    }
  }

  @Test
  void missingOptionsAreUsageErrors() {
    Result result = run("repair");
    assertThat(result.status()).isEqualTo(2);
    assertThat(result.err()).contains("Missing required option: '--scenario=NAME'");
  }

  @Test
  @Timeout(30)
  void aMissingDatasetNamesTheFetchScript(@TempDir Path directory) {
    Path absent = directory.resolve("absent");
    Path output = directory.resolve("output");
    for (List<String> command :
        List.of(List.of("repair", "--scenario", "scenario-001"), List.of("serve", "--port", "0"))) {
      List<String> args = new ArrayList<>(command);
      args.addAll(List.of("--dataset", absent.toString(), "--output", output.toString()));
      Result result = run(args.toArray(String[]::new));
      assertThat(result.status()).isEqualTo(2);
      assertThat(result.err())
          .contains("no dataset at " + absent)
          .contains("run scripts/fetch-dataset.sh or pass --dataset DIR");
    }
    assertThat(output).doesNotExist();
  }
}
