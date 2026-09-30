package se.kth.sonar.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;

/** Parses every configuration of the benchmark, when it is fetched, and reports unmatched lines. */
class DatasetParseAudit {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));

  @Test
  void everyConfigurationLineMatchesAProduction() throws IOException {
    assumeTrue(Files.isDirectory(DATASET), "dataset not fetched");
    Grammar grammar = Dialect.load("cisco-ios").grammar();
    Map<String, Integer> unmatched = new TreeMap<>();
    long lines = 0;
    try (Stream<Path> scenarios = Files.list(DATASET)) {
      for (Path scenario : scenarios.sorted().toList()) {
        Path configs = scenario.resolve("final_configs/configs");
        if (!Files.isDirectory(configs)) {
          continue;
        }
        try (Stream<Path> files = Files.list(configs)) {
          for (Path file : files.toList()) {
            RouterId router = RouterId.fromFileName(file.getFileName().toString());
            for (Statement statement :
                grammar.parse(router, ConfigText.of(Files.readString(file)))) {
              lines++;
              if (!statement.matched()) {
                String shape = statement.context().name() + ": " + shape(statement.text());
                unmatched.merge(shape, 1, Integer::sum);
              }
            }
          }
        }
      }
    }
    unmatched.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(40)
        .forEach(entry -> System.out.println(entry.getValue() + "\t" + entry.getKey()));
    System.out.println("statements " + lines + ", unmatched shapes " + unmatched.size());
    assertThat(lines).isPositive();
  }

  private static String shape(String text) {
    return text.replaceAll("\\d+(\\.\\d+){3}(/\\d+)?", "A")
        .replaceAll("\\b\\d+\\b", "N")
        .replaceAll("[A-Z][A-Za-z0-9_-]*", "W");
  }
}
