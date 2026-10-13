package se.kth.sonar.web.verify;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import se.kth.sonar.batfish.DataPlaneInspector;
import se.kth.sonar.batfish.ForwardingEntry;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.verify.Verification;

/**
 * Verifies the faulty configurations of scenarios on request, one scenario at a time on a worker
 * thread with one data-plane inspector, and keeps the latest reports for the interface to browse.
 *
 * <p>A request is identified by its scenario and specification file. Requesting a verification that
 * is queued, running or finished returns it; requesting a failed one runs it again. Finished and
 * failed jobs beyond the capacity are forgotten, least recently read first. An inspector that fails
 * is discarded, and the next job creates a new one.
 */
public final class ScenarioVerifier implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(ScenarioVerifier.class.getName());
  private static final Duration CLOSE_GRACE = Duration.ofSeconds(5);

  private final ScenarioRepository repository;
  private final Supplier<DataPlaneInspector> inspectors;
  private final Clock clock;
  private final int capacity;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition settled = lock.newCondition();
  private final Map<Key, Job> jobs = new LinkedHashMap<>(16, 0.75f, true);
  private final LinkedBlockingQueue<Key> queue = new LinkedBlockingQueue<>();
  private final Thread worker;
  private DataPlaneInspector inspector;
  private volatile boolean closed;

  /**
   * What one verification checks.
   *
   * @param scenario the scenario whose faulty configurations are verified
   * @param percent the specification file, as the share of the specifications it covers
   */
  public record Key(String scenario, int percent) {
    /** Checks the scenario. */
    public Key {
      Objects.requireNonNull(scenario, "scenario");
    }
  }

  /** Where a verification is. */
  public enum Status {
    QUEUED,
    RUNNING,
    FINISHED,
    FAILED
  }

  /**
   * One requested verification.
   *
   * @param key what it checks
   * @param status where it is
   * @param requested when it was requested
   * @param started when the inspector started on it, once it has
   * @param ended when it finished or failed, once it has
   * @param error why it failed, when it did
   * @param report what the inspector found, once it finished
   */
  public record Job(
      Key key,
      Status status,
      Instant requested,
      Optional<Instant> started,
      Optional<Instant> ended,
      Optional<String> error,
      Optional<Report> report) {
    /** Checks the components. */
    public Job {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(requested, "requested");
      Objects.requireNonNull(started, "started");
      Objects.requireNonNull(ended, "ended");
      Objects.requireNonNull(error, "error");
      Objects.requireNonNull(report, "report");
    }

    /** Returns whether the job is queued or running. */
    public boolean active() {
      return status == Status.QUEUED || status == Status.RUNNING;
    }

    private static Job queued(Key key, Instant at) {
      return new Job(
          key,
          Status.QUEUED,
          at,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    private Job running(Instant at) {
      return new Job(
          key, Status.RUNNING, requested, Optional.of(at), ended, Optional.empty(), report);
    }

    private Job finished(Instant at, Report result) {
      return new Job(
          key,
          Status.FINISHED,
          requested,
          started,
          Optional.of(at),
          Optional.empty(),
          Optional.of(result));
    }

    private Job failed(Instant at, String reason) {
      return new Job(
          key,
          Status.FAILED,
          requested,
          started,
          Optional.of(at),
          Optional.of(reason),
          Optional.empty());
    }
  }

  /**
   * What the inspector found.
   *
   * @param verification the verdict against the specifications and the evidence
   * @param specifications the specifications it was judged against
   * @param forwarding the forwarding table, in router, VRF and route order
   * @param routers how many routers the configurations hold
   */
  public record Report(
      Verification verification,
      SpecificationSet specifications,
      List<ForwardingEntry> forwarding,
      int routers) {
    /** Checks the components and copies the table. */
    public Report {
      Objects.requireNonNull(verification, "verification");
      Objects.requireNonNull(specifications, "specifications");
      forwarding = List.copyOf(forwarding);
    }
  }

  /**
   * Creates a verifier over {@code repository} whose worker inspects with inspectors from {@code
   * inspectors}, keeping at most {@code capacity} finished and failed jobs.
   */
  public ScenarioVerifier(
      ScenarioRepository repository,
      Supplier<DataPlaneInspector> inspectors,
      Clock clock,
      int capacity) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.inspectors = Objects.requireNonNull(inspectors, "inspectors");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (capacity < 1) {
      throw new IllegalArgumentException("the capacity must be positive");
    }
    this.capacity = capacity;
    this.worker = Thread.ofVirtual().name("sonar-verifier").unstarted(this::work);
    worker.start();
  }

  /**
   * The answer to a request.
   *
   * @param job the verification of the requested key
   * @param queued whether the request queued it, as opposed to finding it queued, running or
   *     finished
   */
  public record Requested(Job job, boolean queued) {
    /** Checks the job. */
    public Requested {
      Objects.requireNonNull(job, "job");
    }
  }

  /**
   * Returns the verification of {@code key}, queueing one if none is queued, running or finished.
   */
  public Requested request(Key key) {
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("the verifier is closed");
      }
      Job existing = jobs.get(key);
      if (existing != null && existing.status() != Status.FAILED) {
        return new Requested(existing, false);
      }
      Job job = Job.queued(key, clock.instant());
      jobs.put(key, job);
      forget();
      queue.add(key);
      return new Requested(job, true);
    } finally {
      lock.unlock();
    }
  }

  /** Returns the verification of {@code key}, if one was requested and is still kept. */
  public Optional<Job> job(Key key) {
    lock.lock();
    try {
      return Optional.ofNullable(jobs.get(key));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the verification of {@code key} once it has finished or failed, requesting one if none
   * is kept.
   *
   * @throws InterruptedException if interrupted while waiting
   * @throws IllegalStateException if the verifier is closed before the verification settles
   */
  public Job await(Key key) throws InterruptedException {
    lock.lock();
    try {
      while (true) {
        if (closed) {
          throw new IllegalStateException("the verifier is closed");
        }
        Job job = jobs.get(key);
        if (job == null) {
          job = request(key).job();
        }
        if (!job.active()) {
          return job;
        }
        settled.await();
      }
    } finally {
      lock.unlock();
    }
  }

  /** Stops the worker, waiting briefly for a running inspection, and releases the inspector. */
  @Override
  public void close() {
    lock.lock();
    try {
      closed = true;
      settled.signalAll();
    } finally {
      lock.unlock();
    }
    worker.interrupt();
    try {
      worker.join(CLOSE_GRACE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void work() {
    try {
      while (!closed) {
        Key key;
        try {
          key = queue.take();
        } catch (InterruptedException e) {
          return;
        }
        run(key);
      }
    } finally {
      discard();
    }
  }

  private void run(Key key) {
    update(key, job -> job.running(clock.instant()));
    try {
      RepairProblem problem = repository.load(key.scenario(), key.percent());
      DataPlaneInspector.Report report =
          inspector().inspect(problem.initial(), problem.specifications());
      Verification verification =
          new Verification(
              problem.initial().id(),
              problem.specifications().judge(report.observation().holding()),
              report.observation().evidence());
      Report result =
          new Report(
              verification,
              problem.specifications(),
              report.forwarding(),
              problem.initial().routers().size());
      update(key, job -> job.finished(clock.instant(), result));
    } catch (RuntimeException | Error failure) {
      LOG.log(Level.WARNING, "verifying " + key.scenario() + " failed", failure);
      discard();
      String reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
      update(key, job -> job.failed(clock.instant(), reason));
    }
  }

  private DataPlaneInspector inspector() {
    if (inspector == null) {
      inspector = inspectors.get();
    }
    return inspector;
  }

  private void discard() {
    if (inspector != null) {
      try {
        inspector.close();
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "closing the inspector failed", e);
      }
      inspector = null;
    }
  }

  private void update(Key key, UnaryOperator<Job> change) {
    lock.lock();
    try {
      Job job = jobs.get(key);
      if (job != null) {
        Job changed = change.apply(job);
        jobs.put(key, changed);
        if (!changed.active()) {
          settled.signalAll();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private void forget() {
    long settled = jobs.values().stream().filter(job -> !job.active()).count();
    Iterator<Job> oldest = jobs.values().iterator();
    while (settled > capacity && oldest.hasNext()) {
      if (!oldest.next().active()) {
        oldest.remove();
        settled--;
      }
    }
  }
}
