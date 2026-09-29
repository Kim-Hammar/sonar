package se.kth.sonar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunStoreTest {
  @TempDir Path directory;

  @Test
  void runsThatWereActiveWhenTheServerStoppedBecomeInterrupted() throws IOException {
    RunStore store = new RunStore(directory, Clock.systemUTC());
    String queued;
    String running;
    String finished;
    try (RunLog log = store.create("", List.of("s"), RunSettings.defaults(), true, "m")) {
      queued = log.document().id();
      assertThat(log.document().name()).isEqualTo(queued);
    }
    try (RunLog log = store.create("second", List.of("s"), RunSettings.defaults(), false, "m")) {
      running = log.document().id();
      log.update(document -> document.withStatus(RunStatus.RUNNING, 1, null));
    }
    try (RunLog log = store.create("third", List.of("s"), RunSettings.defaults(), false, "m")) {
      finished = log.document().id();
      log.update(document -> document.withStatus(RunStatus.FINISHED, 2, null));
    }
    Files.createDirectories(directory.resolve("20260101-000000-abcd"));
    Files.writeString(directory.resolve("20260101-000000-abcd/run.json"), "not json");
    List<RunDocument> documents = new RunStore(directory, Clock.systemUTC()).documents();
    assertThat(documents)
        .extracting(RunDocument::id)
        .containsExactlyInAnyOrder(queued, running, finished);
    assertThat(documents)
        .filteredOn(document -> !document.id().equals(finished))
        .allSatisfy(document -> assertThat(document.runStatus()).isEqualTo(RunStatus.INTERRUPTED));
    assertThat(documents)
        .filteredOn(document -> document.id().equals(finished))
        .singleElement()
        .satisfies(document -> assertThat(document.runStatus()).isEqualTo(RunStatus.FINISHED));
  }

  @Test
  void settingsSurviveTheRoundTripThroughRunJson() throws IOException {
    RunStore store = new RunStore(directory, Clock.systemUTC());
    RunSettings settings = RunSettings.defaults();
    try (RunLog log = store.create("x", List.of("s1", "s2"), settings, false, "m")) {
      RunDocument read = RunLog.readDocument(log.directory());
      assertThat(read.settings().toRunSettings()).isEqualTo(settings);
      assertThat(read.scenarios()).containsExactly("s1", "s2");
    }
  }

  @Test
  void rejectsMalformedIdentifiers() {
    RunStore store = new RunStore(directory, Clock.systemUTC());
    assertThat(RunStore.validId("20260929-120000-0a1b")).isTrue();
    assertThat(RunStore.validId("../etc")).isFalse();
    assertThatThrownBy(() -> store.open("../etc")).isInstanceOf(IllegalArgumentException.class);
  }
}
