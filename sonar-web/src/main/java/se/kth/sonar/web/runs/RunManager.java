package se.kth.sonar.web.runs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantLock;
import se.kth.sonar.app.RunDocument;
import se.kth.sonar.app.RunLog;
import se.kth.sonar.app.RunSettings;
import se.kth.sonar.app.RunStatus;
import se.kth.sonar.app.RunStore;
import se.kth.sonar.execution.Cancellation;

/**
 * Queues runs and executes them one at a time on a virtual thread, because the data-plane engines
 * of one run already use most of the machine's memory.
 *
 * <p>Runs stored by earlier processes are listed from the start. Cancelling a queued run removes it
 * from the queue; cancelling the running run sets its cancellation token and, if the run has not
 * stopped after a grace period, interrupts it.
 */
public final class RunManager implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(RunManager.class.getName());
  private static final Duration GRACE = Duration.ofSeconds(10);

  private final RunStore store;
  private final RunLauncher launcher;
  private final Clock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, RunDocument> documents = new LinkedHashMap<>();
  private final Map<String, RunLog> logs = new LinkedHashMap<>();
  private final Map<String, Cancellation> cancellations = new LinkedHashMap<>();
  private final LinkedBlockingDeque<String> queue = new LinkedBlockingDeque<>();
  private final Thread worker;
  private volatile String running;
  private volatile boolean closed;

  /** Creates a manager over {@code store} and starts its worker. */
  public RunManager(RunStore store, RunLauncher launcher, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
    this.clock = Objects.requireNonNull(clock, "clock");
    for (RunDocument document : store.documents()) {
      documents.put(document.id(), document);
    }
    this.worker = Thread.ofVirtual().name("sonar-runs").unstarted(this::work);
    worker.start();
  }

  /** Creates a queued run and returns its document. */
  public RunDocument submit(
      String name, List<String> scenarios, RunSettings settings, boolean demo, String model)
      throws IOException {
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("the server is shutting down");
      }
      RunLog log = store.create(name, scenarios, settings, demo, model);
      RunDocument document = log.document();
      documents.put(document.id(), document);
      logs.put(document.id(), log);
      cancellations.put(document.id(), new Cancellation());
      queue.add(document.id());
      return document;
    } finally {
      lock.unlock();
    }
  }

  /** Returns every run, newest first. */
  public List<RunDocument> runs() {
    lock.lock();
    try {
      List<RunDocument> runs = new ArrayList<>(documents.size());
      for (String id : documents.keySet()) {
        runs.add(current(id));
      }
      Collections.reverse(runs);
      return runs;
    } finally {
      lock.unlock();
    }
  }

  /** Returns the run {@code id}, if it exists. */
  public Optional<RunDocument> run(String id) {
    lock.lock();
    try {
      return documents.containsKey(id) ? Optional.of(current(id)) : Optional.empty();
    } finally {
      lock.unlock();
    }
  }

  /** Returns the log of run {@code id}, opening it on first use. */
  public Optional<RunLog> log(String id) throws IOException {
    lock.lock();
    try {
      if (!documents.containsKey(id)) {
        return Optional.empty();
      }
      RunLog log = logs.get(id);
      if (log == null) {
        log = store.open(id);
        logs.put(id, log);
      }
      return Optional.of(log);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Cancels run {@code id} if it is queued or running and returns its document.
   *
   * @throws IllegalStateException if the run has already ended
   */
  public Optional<RunDocument> cancel(String id) throws IOException {
    lock.lock();
    try {
      if (!documents.containsKey(id)) {
        return Optional.empty();
      }
      if (current(id).runStatus().terminal()) {
        throw new IllegalStateException("the execution has already ended");
      }
      Cancellation cancellation = cancellations.get(id);
      cancellation.cancel();
      if (queue.remove(id)) {
        finish(id, RunStatus.CANCELLED, null);
      } else if (id.equals(running)) {
        Thread.ofVirtual().name("sonar-cancel").start(() -> interruptAfterGrace(id));
      }
      return Optional.of(current(id));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Deletes run {@code id} and everything it recorded, and returns whether it existed.
   *
   * @throws IllegalStateException if the run is queued or running; it must be cancelled first
   */
  public boolean delete(String id) throws IOException {
    lock.lock();
    try {
      if (!documents.containsKey(id)) {
        return false;
      }
      if (!current(id).runStatus().terminal()) {
        throw new IllegalStateException("the execution has not ended; cancel it first");
      }
      RunLog log = logs.remove(id);
      if (log != null) {
        log.close();
      }
      documents.remove(id);
      cancellations.remove(id);
      store.delete(id);
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** Stops the worker, cancels the running run, and closes every log. */
  @Override
  public void close() {
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      cancellations.values().forEach(Cancellation::cancel);
    } finally {
      lock.unlock();
    }
    worker.interrupt();
    try {
      worker.join(GRACE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    lock.lock();
    try {
      for (String id : List.copyOf(queue)) {
        finish(id, RunStatus.INTERRUPTED, "the server stopped");
      }
      queue.clear();
      logs.values().forEach(RunLog::close);
    } finally {
      lock.unlock();
    }
  }

  private void work() {
    while (!closed) {
      String id;
      try {
        id = queue.take();
      } catch (InterruptedException e) {
        return;
      }
      execute(id);
    }
  }

  private void execute(String id) {
    RunLog log;
    Cancellation cancellation;
    lock.lock();
    try {
      log = logs.get(id);
      cancellation = cancellations.get(id);
      running = id;
      update(log, RunStatus.RUNNING, null);
    } finally {
      lock.unlock();
    }
    RunStatus status = RunStatus.FINISHED;
    String error = null;
    try {
      launcher.launch(log.document(), cancellation, log);
      if (log.failure().isPresent()) {
        status = RunStatus.FAILED;
        error = "the execution log could not be written: " + log.failure().get().getMessage();
      }
    } catch (RuntimeException failure) {
      status = RunStatus.FAILED;
      error =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      LOG.log(Level.WARNING, "run " + id + " failed", failure);
    }
    if (cancellation.isCancelled()) {
      status = closed ? RunStatus.INTERRUPTED : RunStatus.CANCELLED;
      error = closed ? "the server stopped" : null;
    }
    Thread.interrupted();
    lock.lock();
    try {
      running = null;
      finish(id, status, error);
    } finally {
      lock.unlock();
    }
  }

  private void interruptAfterGrace(String id) {
    try {
      Thread.sleep(GRACE);
    } catch (InterruptedException e) {
      return;
    }
    if (id.equals(running)) {
      worker.interrupt();
    }
  }

  private void finish(String id, RunStatus status, String error) {
    RunLog log = logs.get(id);
    if (log != null) {
      update(log, status, error);
    }
  }

  private void update(RunLog log, RunStatus status, String error) {
    try {
      RunDocument document =
          log.update(current -> current.withStatus(status, clock.millis(), error));
      documents.put(document.id(), document);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot record the status of run " + log.document().id(), e);
    }
  }

  private RunDocument current(String id) {
    RunLog log = logs.get(id);
    return log == null ? documents.get(id) : log.document();
  }
}
