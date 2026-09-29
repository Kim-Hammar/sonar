package se.kth.sonar.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceRecord;

class RunLogTest {
  @TempDir Path directory;

  private static TraceRecord record(String scenario, String reason) {
    return new TraceRecord(
        scenario,
        Instant.ofEpochMilli(1_000),
        Map.of(Budget.Resource.DECISIONS, 2L),
        new TraceEvent.Failed(reason));
  }

  private RunLog create() throws IOException {
    return new RunStore(directory, Clock.systemUTC())
        .create("test", List.of("a", "b"), RunSettings.defaults(), false, "model");
  }

  private static JsonNode json(String line) throws IOException {
    return Json.mapper().readTree(line);
  }

  @Test
  void summariesAndDetailsAreNumberedPerRunAndPerScenario() throws IOException {
    try (RunLog log = create()) {
      log.accept(record("a", "one"));
      log.accept(record("b", "two"));
      log.accept(record("a", "three"));
      assertThat(log.size()).isEqualTo(3);
      List<String> summaries = log.summaries(0, 10);
      assertThat(summaries).hasSize(3);
      JsonNode third = json(summaries.get(2));
      assertThat(third.path("seq").asLong()).isEqualTo(3);
      assertThat(third.path("step").asLong()).isEqualTo(2);
      assertThat(third.path("scenario").asText()).isEqualTo("a");
      assertThat(third.path("usage").path("decisions").asLong()).isEqualTo(2);
      assertThat(log.summaries(1, 1))
          .singleElement()
          .satisfies(line -> assertThat(json(line).path("seq").asLong()).isEqualTo(2));
      assertThat(json(log.detail(3).orElseThrow()).path("reason").asText()).isEqualTo("three");
      assertThat(log.detail(4)).isEmpty();
      assertThat(log.summaries(3, 10)).isEmpty();
    }
  }

  @Test
  void reopeningCutsAnIncompleteTail() throws IOException {
    Path run;
    long eventsSize;
    try (RunLog log = create()) {
      run = log.directory();
      log.accept(record("a", "one"));
      log.accept(record("b", "two"));
      eventsSize = Files.size(run.resolve(RunLog.EVENTS));
    }
    Files.writeString(
        run.resolve(RunLog.DETAILS),
        "{\"seq\":3,\"type\"",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    Files.writeString(
        run.resolve(RunLog.EVENTS),
        "{\"seq\":3",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
    try (RunLog reopened = RunLog.open(run)) {
      assertThat(reopened.size()).isEqualTo(2);
      assertThat(json(reopened.detail(2).orElseThrow()).path("reason").asText()).isEqualTo("two");
      reopened.accept(record("a", "three"));
      assertThat(json(reopened.summaries(2, 1).getFirst()).path("step").asLong()).isEqualTo(2);
    }
    assertThat(Files.size(run.resolve(RunLog.EVENTS))).isGreaterThan(eventsSize);
  }

  @Test
  void concurrentScenariosGetUniqueNumbersAndOrderedSteps() throws Exception {
    try (RunLog log = create();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> writers = new ArrayList<>();
      for (String scenario : List.of("a", "b", "c", "d")) {
        writers.add(
            executor.submit(
                () -> {
                  for (int index = 0; index < 50; index++) {
                    log.accept(record(scenario, scenario + index));
                  }
                }));
      }
      for (Future<?> writer : writers) {
        writer.get();
      }
      List<String> summaries = log.summaries(0, 1000);
      assertThat(summaries).hasSize(200);
      for (int index = 0; index < summaries.size(); index++) {
        assertThat(json(summaries.get(index)).path("seq").asLong()).isEqualTo(index + 1L);
      }
      for (String scenario : List.of("a", "b", "c", "d")) {
        List<String> reasons = new ArrayList<>();
        long expectedStep = 1;
        for (String line : summaries) {
          JsonNode node = json(line);
          if (node.path("scenario").asText().equals(scenario)) {
            assertThat(node.path("step").asLong()).isEqualTo(expectedStep++);
            reasons.add(node.path("reason").asText());
          }
        }
        assertThat(reasons).first().isEqualTo(scenario + 0);
        assertThat(reasons).last().isEqualTo(scenario + 49);
      }
    }
  }

  @Test
  void waitingReadersWakeOnAppendsAndWhenTheRunEnds() throws Exception {
    try (RunLog log = create();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Boolean> waiter = executor.submit(() -> log.awaitAfter(0, Duration.ofSeconds(10)));
      Thread.sleep(50);
      log.accept(record("a", "one"));
      assertThat(waiter.get()).isTrue();
      log.update(document -> document.withStatus(RunStatus.FINISHED, 5, null));
      assertThat(log.sealed()).isTrue();
      long started = System.nanoTime();
      assertThat(log.awaitAfter(1, Duration.ofSeconds(10))).isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
      log.accept(record("a", "ignored"));
      assertThat(log.size()).isEqualTo(1);
    }
  }

  @Test
  void documentUpdatesAreAtomicAndWriteFailuresAreKept() throws IOException {
    RunLog log = create();
    Path run = log.directory();
    log.update(document -> document.withStatus(RunStatus.RUNNING, 7, null));
    assertThat(RunLog.readDocument(run).runStatus()).isEqualTo(RunStatus.RUNNING);
    assertThat(RunLog.readDocument(run).started()).isEqualTo(7L);
    assertThat(Files.exists(run.resolve(RunLog.DOCUMENT + ".tmp"))).isFalse();
    log.close();
    log.accept(record("a", "after close"));
    assertThat(log.failure()).isPresent();
  }
}
