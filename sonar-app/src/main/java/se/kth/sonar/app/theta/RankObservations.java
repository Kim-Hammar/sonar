package se.kth.sonar.app.theta;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import se.kth.sonar.app.Json;

/**
 * Reads, from recorded runs, the rank at which the decision model placed the first valid repair of
 * a region.
 *
 * <p>Each ranking the model answered is one episode. A verified candidate belongs to the most
 * recent ranking of its scenario that lists it, since a ranking survives into later epochs when its
 * region is unchanged. A candidate is a valid repair when its verification fixed specifications and
 * broke none. An episode is complete when a valid repair was found and every candidate ranked above
 * the first one was verified; its rank counts from one. An episode without a valid repair is
 * censored: its region may contain none. Regions whose candidates were never ranked are not
 * episodes, because their order is the generator's rather than the model's.
 *
 * @param ranks the rank of the first valid repair of every complete episode
 * @param censored the episodes in which no valid repair was found
 * @param incomplete the episodes whose first valid repair was found before every candidate ranked
 *     above it was verified
 * @param scenarios the scenario of each rank in {@code ranks}, in the same order
 */
public record RankObservations(
    List<Integer> ranks, int censored, int incomplete, List<String> scenarios) {

  private static final String IMPROVEMENT = "improvement";

  /** Copies the lists. */
  public RankObservations {
    ranks = List.copyOf(ranks);
    scenarios = List.copyOf(scenarios);
    if (ranks.size() != scenarios.size()) {
      throw new IllegalArgumentException("every rank needs its scenario");
    }
  }

  /** Returns the episodes counted in the fit. */
  public int episodes() {
    return ranks.size();
  }

  /**
   * Reads every run in {@code paths}; each path is a run directory, which holds {@code
   * events.jsonl} and {@code details.jsonl}, or a directory of such run directories.
   */
  public static RankObservations read(List<Path> paths) throws IOException {
    List<Path> runs = new ArrayList<>();
    for (Path path : paths) {
      if (Files.isRegularFile(path.resolve("details.jsonl"))) {
        runs.add(path);
        continue;
      }
      try (Stream<Path> children = Files.list(path)) {
        children
            .filter(child -> Files.isRegularFile(child.resolve("details.jsonl")))
            .sorted()
            .forEach(runs::add);
      }
    }
    List<Integer> ranks = new ArrayList<>();
    List<String> scenarios = new ArrayList<>();
    int censored = 0;
    int incomplete = 0;
    for (Path run : runs) {
      for (Episode episode : episodes(run)) {
        switch (episode.outcome()) {
          case Outcome.Complete complete -> {
            ranks.add(complete.rank());
            scenarios.add(episode.scenario());
          }
          case Outcome.Censored _ -> censored++;
          case Outcome.Incomplete _ -> incomplete++;
        }
      }
    }
    return new RankObservations(ranks, censored, incomplete, scenarios);
  }

  private sealed interface Outcome {
    record Complete(int rank) implements Outcome {}

    record Censored() implements Outcome {}

    record Incomplete() implements Outcome {}
  }

  private record Episode(String scenario, Outcome outcome) {}

  /** One answered ranking and what verification showed about its candidates. */
  private static final class Ranking {
    private final String scenario;
    private final Map<String, Integer> position = new HashMap<>();
    private final Boolean[] valid;

    Ranking(String scenario, List<String> order) {
      this.scenario = scenario;
      for (int index = 0; index < order.size(); index++) {
        position.putIfAbsent(order.get(index), index);
      }
      this.valid = new Boolean[order.size()];
    }

    boolean lists(String candidate) {
      return position.containsKey(candidate);
    }

    void verified(String candidate, boolean isValid) {
      int index = position.get(candidate);
      if (valid[index] == null) {
        valid[index] = isValid;
      }
    }

    Episode episode() {
      for (int index = 0; index < valid.length; index++) {
        if (Boolean.TRUE.equals(valid[index])) {
          for (int above = 0; above < index; above++) {
            if (valid[above] == null) {
              return new Episode(scenario, new Outcome.Incomplete());
            }
          }
          return new Episode(scenario, new Outcome.Complete(index + 1));
        }
      }
      return new Episode(scenario, new Outcome.Censored());
    }
  }

  private static List<Episode> episodes(Path run) throws IOException {
    Map<Long, String> scenarioOf = new HashMap<>();
    try (BufferedReader events = Files.newBufferedReader(run.resolve("events.jsonl"))) {
      for (String line = events.readLine(); line != null; line = events.readLine()) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode event = Json.mapper().readTree(line);
        if (event.hasNonNull("scenario")) {
          scenarioOf.put(event.path("seq").asLong(), event.path("scenario").asText());
        }
      }
    }
    Map<String, List<Ranking>> rankings = new LinkedHashMap<>();
    List<Ranking> all = new ArrayList<>();
    try (BufferedReader details = Files.newBufferedReader(run.resolve("details.jsonl"))) {
      for (String line = details.readLine(); line != null; line = details.readLine()) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode detail = Json.mapper().readTree(line);
        String scenario = scenarioOf.get(detail.path("seq").asLong());
        if (scenario == null) {
          continue;
        }
        String type = detail.path("type").asText();
        if (type.equals("decided")
            && detail.path("question").asText().equals("hypothesis")
            && detail.path("outcome").asText().equals("answered")) {
          List<String> order = new ArrayList<>();
          detail
              .path("answer")
              .path("order")
              .forEach(entry -> order.add(entry.path("id").asText()));
          Ranking ranking = new Ranking(scenario, order);
          rankings.computeIfAbsent(scenario, name -> new ArrayList<>()).add(ranking);
          all.add(ranking);
        } else if (type.equals("tried")) {
          String candidate = detail.path("hypothesis").path("id").asText();
          List<Ranking> ofScenario = rankings.getOrDefault(scenario, List.of());
          for (int index = ofScenario.size() - 1; index >= 0; index--) {
            if (ofScenario.get(index).lists(candidate)) {
              ofScenario
                  .get(index)
                  .verified(candidate, detail.path("change").asText().equals(IMPROVEMENT));
              break;
            }
          }
        }
      }
    }
    return all.stream().map(Ranking::episode).toList();
  }
}
