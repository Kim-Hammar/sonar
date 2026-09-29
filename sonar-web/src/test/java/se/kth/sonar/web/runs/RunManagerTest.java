package se.kth.sonar.web.runs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.RunDocument;
import se.kth.sonar.app.RunSettings;
import se.kth.sonar.app.RunStatus;
import se.kth.sonar.app.RunStore;

class RunManagerTest {
  @TempDir Path directory;

  private static void pause(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static RunStatus await(RunManager manager, String id, RunStatus status)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      RunStatus current = manager.run(id).orElseThrow().runStatus();
      if (current == status) {
        return current;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(id + " never became " + status);
  }

  @Test
  void runsExecuteOneAtATimeInSubmissionOrder() throws Exception {
    List<String> started = new CopyOnWriteArrayList<>();
    CountDownLatch release = new CountDownLatch(1);
    RunLauncher launcher =
        (run, cancellation, sink) -> {
          started.add(run.name());
          if (run.name().equals("first")) {
            pause(release);
          }
          return List.of();
        };
    try (RunManager manager =
        new RunManager(new RunStore(directory, Clock.systemUTC()), launcher, Clock.systemUTC())) {
      RunDocument first = manager.submit("first", List.of("s"), RunSettings.defaults(), false, "m");
      RunDocument second =
          manager.submit("second", List.of("s"), RunSettings.defaults(), false, "m");
      await(manager, first.id(), RunStatus.RUNNING);
      assertThat(manager.run(second.id()).orElseThrow().runStatus()).isEqualTo(RunStatus.QUEUED);
      release.countDown();
      await(manager, second.id(), RunStatus.FINISHED);
      assertThat(started).containsExactly("first", "second");
      assertThat(manager.runs())
          .extracting(RunDocument::id)
          .containsExactly(second.id(), first.id());
    }
  }

  @Test
  void aFailingLauncherFailsTheRunAndClosingInterruptsQueuedRuns() throws Exception {
    CountDownLatch hold = new CountDownLatch(1);
    RunLauncher launcher =
        (run, cancellation, sink) -> {
          if (run.name().equals("broken")) {
            throw new IllegalStateException("no engine");
          }
          pause(hold);
          return List.of();
        };
    RunManager manager =
        new RunManager(new RunStore(directory, Clock.systemUTC()), launcher, Clock.systemUTC());
    RunDocument broken = manager.submit("broken", List.of("s"), RunSettings.defaults(), false, "m");
    await(manager, broken.id(), RunStatus.FAILED);
    assertThat(manager.run(broken.id()).orElseThrow().error()).isEqualTo("no engine");
    RunDocument holding =
        manager.submit("holding", List.of("s"), RunSettings.defaults(), false, "m");
    RunDocument waiting =
        manager.submit("waiting", List.of("s"), RunSettings.defaults(), false, "m");
    await(manager, holding.id(), RunStatus.RUNNING);
    manager.close();
    hold.countDown();
    assertThat(manager.run(holding.id()).orElseThrow().runStatus())
        .isEqualTo(RunStatus.INTERRUPTED);
    assertThat(manager.run(waiting.id()).orElseThrow().runStatus())
        .isEqualTo(RunStatus.INTERRUPTED);
  }
}
