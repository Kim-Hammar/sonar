package se.kth.sonar.app.theta;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RankObservationsTest {
  @TempDir Path runs;

  private final List<String> events = new ArrayList<>();
  private final List<String> details = new ArrayList<>();

  private void record(String scenario, String detail) {
    int seq = events.size() + 1;
    events.add("{\"seq\":" + seq + ",\"scenario\":\"" + scenario + "\"}");
    details.add("{\"seq\":" + seq + "," + detail + "}");
  }

  private void ranked(String scenario, String... order) {
    StringBuilder ids = new StringBuilder();
    for (String id : order) {
      ids.append(ids.isEmpty() ? "" : ",").append("{\"id\":\"").append(id).append("\"}");
    }
    record(
        scenario,
        "\"type\":\"decided\",\"question\":\"hypothesis\",\"outcome\":\"answered\","
            + "\"answer\":{\"order\":["
            + ids
            + "]}");
  }

  private void tried(String scenario, String id, String change) {
    record(
        scenario,
        "\"type\":\"tried\",\"region\":\"r\",\"hypothesis\":{\"id\":\""
            + id
            + "\"},\"change\":\""
            + change
            + "\"");
  }

  private Path write(String name) throws IOException {
    Path run = Files.createDirectories(runs.resolve(name));
    Files.write(run.resolve("events.jsonl"), events);
    Files.write(run.resolve("details.jsonl"), details);
    return run;
  }

  @Test
  void theFirstValidRepairIsRankedFromOneAcrossEpochs() throws IOException {
    ranked("s1", "a", "b", "c", "d");
    tried("s1", "a", "no-effect");
    tried("s1", "b", "mixed");
    record("s1", "\"type\":\"adopted\"");
    tried("s1", "c", "improvement");
    tried("s1", "d", "improvement");
    RankObservations observations = RankObservations.read(List.of(write("run")));
    assertThat(observations.ranks()).containsExactly(3);
    assertThat(observations.scenarios()).containsExactly("s1");
    assertThat(observations.censored()).isZero();
  }

  @Test
  void rankingsWithoutAValidRepairAreCensoredAndUnrankedRegionsIgnored() throws IOException {
    ranked("s1", "a", "b");
    tried("s1", "a", "regression");
    tried("s1", "x", "improvement");
    ranked("s2", "p", "q");
    tried("s2", "q", "improvement");
    RankObservations observations = RankObservations.read(List.of(write("run")));
    assertThat(observations.ranks()).isEmpty();
    assertThat(observations.censored()).isEqualTo(1);
    assertThat(observations.incomplete()).isEqualTo(1);
  }

  @Test
  void aCandidateBelongsToTheLatestRankingOfItsOwnScenario() throws IOException {
    ranked("s1", "a", "b");
    ranked("s2", "a", "b");
    ranked("s1", "b", "a");
    tried("s1", "b", "improvement");
    tried("s2", "a", "improvement");
    write("run");
    RankObservations observations = RankObservations.read(List.of(runs));
    assertThat(observations.ranks()).containsExactly(1, 1);
    assertThat(observations.scenarios()).containsExactlyInAnyOrder("s1", "s2");
    assertThat(observations.censored()).isEqualTo(1);
  }
}
