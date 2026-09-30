package se.kth.sonar.web.explore;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import se.kth.sonar.app.Pipeline;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.engine.Epoch;
import se.kth.sonar.engine.EpochBuilder;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.prior.PriorFactor;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.web.verify.ScenarioVerifier;

/**
 * Builds the search space a repair would start from, for scenarios on request, and generates the
 * candidates of its regions when asked: the first epoch of the engine, with the same builder and
 * prior a run uses, over the verification the {@link ScenarioVerifier} computed for the scenario.
 *
 * <p>An exploration is identified by its scenario and specification file. Requesting one that is
 * queued, running or finished returns it; requesting a failed one builds it again. Candidates are
 * generated region by region on the same worker that builds, one task at a time. Explorations
 * beyond the capacity are forgotten, least recently read first, but never while they have work
 * queued or running.
 */
public final class SearchExplorer implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(SearchExplorer.class.getName());
  private static final Duration CLOSE_GRACE = Duration.ofSeconds(5);

  private final ScenarioRepository repository;
  private final ScenarioVerifier verifier;
  private final ExecutionContext context;
  private final EpochBuilder epochs;
  private final Clock clock;
  private final int capacity;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Key, Exploration> explorations = new LinkedHashMap<>(16, 0.75f, true);
  private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
  private final Thread worker;
  private volatile boolean closed;

  /**
   * What one exploration covers.
   *
   * @param scenario the scenario whose faulty configurations are searched
   * @param percent the specification file, as the share of the specifications it covers
   */
  public record Key(String scenario, int percent) {
    /** Checks the scenario. */
    public Key {
      Objects.requireNonNull(scenario, "scenario");
    }
  }

  /** Where an exploration is. */
  public enum Status {
    QUEUED,
    VERIFYING,
    BUILDING,
    FINISHED,
    FAILED
  }

  /**
   * The generation of one region's candidates.
   *
   * @param state where it is
   * @param requested when it was requested
   * @param started when the worker started it, once it has
   * @param ended when it ended, once it has
   * @param error why it failed, when it did
   */
  public record Expansion(
      State state,
      Instant requested,
      Optional<Instant> started,
      Optional<Instant> ended,
      Optional<String> error) {

    /** Where the generation is; a region without an expansion is deferred. */
    public enum State {
      QUEUED,
      RUNNING,
      ADMITTED,
      DROPPED,
      FAILED
    }

    /** Checks the components. */
    public Expansion {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(requested, "requested");
      Objects.requireNonNull(started, "started");
      Objects.requireNonNull(ended, "ended");
      Objects.requireNonNull(error, "error");
    }

    /** Returns whether the generation is queued or running. */
    public boolean active() {
      return state == State.QUEUED || state == State.RUNNING;
    }

    private static Expansion queued(Instant at) {
      return new Expansion(State.QUEUED, at, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Expansion running(Instant at) {
      return new Expansion(State.RUNNING, requested, Optional.of(at), ended, error);
    }

    private Expansion ended(State state, Instant at) {
      return new Expansion(state, requested, started, Optional.of(at), Optional.empty());
    }

    private Expansion failed(Instant at, String reason) {
      return new Expansion(State.FAILED, requested, started, Optional.of(at), Optional.of(reason));
    }
  }

  /**
   * A built search space with everything derived from it.
   *
   * @param epoch the state and the space
   * @param prior the prior over the space's regions
   * @param breakdown each factor's weight for every region, in region and factor order
   * @param expansions the candidate generations requested so far, by region
   * @param dropped the regions the engine drops because no candidate passed admissibility
   */
  public record Model(
      Epoch epoch,
      Distribution<Region.Id> prior,
      SequencedMap<Region.Id, SequencedMap<String, Double>> breakdown,
      Map<Region.Id, Expansion> expansions,
      Map<Region.Id, Region> dropped) {

    /** Checks and copies the components. */
    public Model {
      Objects.requireNonNull(epoch, "epoch");
      Objects.requireNonNull(prior, "prior");
      breakdown = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(breakdown));
      expansions = Map.copyOf(expansions);
      dropped = Map.copyOf(dropped);
    }

    /** Returns whether any candidate generation is queued or running. */
    public boolean expanding() {
      return expansions.values().stream().anyMatch(Expansion::active);
    }

    private static Model of(
        EpochBuilder epochs,
        Epoch epoch,
        Map<Region.Id, Expansion> expansions,
        Map<Region.Id, Region> dropped) {
      SequencedMap<Region.Id, SequencedMap<String, Double>> breakdown = new LinkedHashMap<>();
      for (Region region : epoch.space().regions()) {
        breakdown.put(region.id(), epochs.prior().breakdown(region, epoch.state()));
      }
      return new Model(epoch, epochs.prior(epoch), breakdown, expansions, dropped);
    }

    private Model withExpansion(Region.Id id, Expansion expansion) {
      Map<Region.Id, Expansion> changed = new HashMap<>(expansions);
      changed.put(id, expansion);
      return new Model(epoch, prior, breakdown, changed, dropped);
    }
  }

  /**
   * One requested exploration.
   *
   * @param key what it covers
   * @param status where it is
   * @param requested when it was requested
   * @param started when the worker started it, once it has
   * @param ended when it finished or failed, once it has
   * @param error why it failed, when it did
   * @param model the built search space, once finished
   */
  public record Exploration(
      Key key,
      Status status,
      Instant requested,
      Optional<Instant> started,
      Optional<Instant> ended,
      Optional<String> error,
      Optional<Model> model) {

    /** Checks the components. */
    public Exploration {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(requested, "requested");
      Objects.requireNonNull(started, "started");
      Objects.requireNonNull(ended, "ended");
      Objects.requireNonNull(error, "error");
      Objects.requireNonNull(model, "model");
    }

    /** Returns whether the build or a candidate generation is queued or running. */
    public boolean active() {
      return status == Status.QUEUED
          || status == Status.VERIFYING
          || status == Status.BUILDING
          || model.map(Model::expanding).orElse(false);
    }

    private static Exploration queued(Key key, Instant at) {
      return new Exploration(
          key,
          Status.QUEUED,
          at,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    private Exploration at(Status status, Instant startedAt) {
      return new Exploration(
          key, status, requested, Optional.of(startedAt), ended, Optional.empty(), model);
    }

    private Exploration finished(Instant at, Model built) {
      return new Exploration(
          key,
          Status.FINISHED,
          requested,
          started,
          Optional.of(at),
          Optional.empty(),
          Optional.of(built));
    }

    private Exploration failed(Instant at, String reason) {
      return new Exploration(
          key,
          Status.FAILED,
          requested,
          started,
          Optional.of(at),
          Optional.of(reason),
          Optional.empty());
    }

    private Exploration withModel(Model changed) {
      return new Exploration(key, status, requested, started, ended, error, Optional.of(changed));
    }
  }

  /**
   * The answer to a request.
   *
   * @param exploration the exploration of the requested key
   * @param queued whether the request queued work, as opposed to finding it queued, running or done
   */
  public record Requested(Exploration exploration, boolean queued) {
    /** Checks the exploration. */
    public Requested {
      Objects.requireNonNull(exploration, "exploration");
    }
  }

  private sealed interface Task {
    record Build(Key key) implements Task {}

    record Expand(Key key, Region.Id region) implements Task {}
  }

  /**
   * Creates an explorer over {@code repository} whose baselines come from {@code verifier}, with
   * the epoch builder of {@code dialect} on a context of {@code parallelism}, keeping at most
   * {@code capacity} settled explorations.
   */
  public SearchExplorer(
      ScenarioRepository repository,
      ScenarioVerifier verifier,
      Dialect dialect,
      ParallelismConfig parallelism,
      Clock clock,
      int capacity) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (capacity < 1) {
      throw new IllegalArgumentException("the capacity must be positive");
    }
    this.capacity = capacity;
    this.context = ExecutionContext.open(parallelism, List.of());
    this.epochs = Pipeline.epochs(dialect, context);
    this.worker = Thread.ofVirtual().name("sonar-explorer").unstarted(this::work);
    worker.start();
  }

  /** Returns the names of the prior's factors, in the order they multiply. */
  public List<String> factors() {
    return epochs.prior().factors().stream().map(PriorFactor::name).toList();
  }

  /** Returns the exploration of {@code key}, queueing one if none is queued, running or done. */
  public Requested request(Key key) {
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("the explorer is closed");
      }
      Exploration existing = explorations.get(key);
      if (existing != null && existing.status() != Status.FAILED) {
        return new Requested(existing, false);
      }
      Exploration exploration = Exploration.queued(key, clock.instant());
      explorations.put(key, exploration);
      forget();
      queue.add(new Task.Build(key));
      return new Requested(exploration, true);
    } finally {
      lock.unlock();
    }
  }

  /** Returns the exploration of {@code key}, if one was requested and is still kept. */
  public Optional<Exploration> exploration(Key key) {
    lock.lock();
    try {
      return Optional.ofNullable(explorations.get(key));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the exploration of {@code key} with the candidates of region {@code region} queued for
   * generation, unless they are queued, being generated or generated already.
   *
   * @throws IllegalStateException if the exploration is not finished
   * @throws NoSuchElementException if the space has no such region
   */
  public Requested expand(Key key, Region.Id region) {
    lock.lock();
    try {
      Exploration exploration = explorations.get(key);
      if (exploration == null || exploration.status() != Status.FINISHED) {
        throw new IllegalStateException("the search space of " + key.scenario() + " is not built");
      }
      Model model = exploration.model().orElseThrow();
      Expansion existing = model.expansions().get(region);
      if (existing != null && existing.state() != Expansion.State.FAILED) {
        return new Requested(exploration, false);
      }
      if (model.epoch().space().region(region).isEmpty()) {
        throw new NoSuchElementException("no region " + region.value());
      }
      Exploration changed =
          exploration.withModel(model.withExpansion(region, Expansion.queued(clock.instant())));
      explorations.put(key, changed);
      queue.add(new Task.Expand(key, region));
      return new Requested(changed, true);
    } finally {
      lock.unlock();
    }
  }

  /** Stops the worker, waiting briefly for its current task, and closes the execution context. */
  @Override
  public void close() {
    closed = true;
    worker.interrupt();
    try {
      worker.join(CLOSE_GRACE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    context.close();
  }

  private void work() {
    while (!closed) {
      Task task;
      try {
        task = queue.take();
      } catch (InterruptedException e) {
        return;
      }
      try {
        switch (task) {
          case Task.Build build -> build(build.key());
          case Task.Expand expand -> expand(expand);
        }
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private void build(Key key) throws InterruptedException {
    update(key, exploration -> exploration.at(Status.VERIFYING, clock.instant()));
    ScenarioVerifier.Key baseline = new ScenarioVerifier.Key(key.scenario(), key.percent());
    ScenarioVerifier.Job verification;
    try {
      verifier.request(baseline);
      verification = verifier.await(baseline);
    } catch (IllegalStateException e) {
      update(key, exploration -> exploration.failed(clock.instant(), e.getMessage()));
      return;
    }
    if (verification.report().isEmpty()) {
      String reason = "the verification failed: " + verification.error().orElse("unknown error");
      update(key, exploration -> exploration.failed(clock.instant(), reason));
      return;
    }
    update(
        key, exploration -> exploration.at(Status.BUILDING, exploration.started().orElseThrow()));
    try {
      RepairProblem problem = repository.load(key.scenario(), key.percent());
      Epoch epoch = epochs.initial(problem, verification.report().orElseThrow().verification());
      Model model = Model.of(epochs, epoch, Map.of(), Map.of());
      update(key, exploration -> exploration.finished(clock.instant(), model));
    } catch (RuntimeException | Error failure) {
      LOG.log(Level.WARNING, "building the search space of " + key.scenario() + " failed", failure);
      update(key, exploration -> exploration.failed(clock.instant(), message(failure)));
    }
  }

  private void expand(Task.Expand task) {
    Model model;
    lock.lock();
    try {
      Exploration exploration = explorations.get(task.key());
      if (exploration == null || exploration.status() != Status.FINISHED) {
        return;
      }
      Model current = exploration.model().orElseThrow();
      Expansion expansion = current.expansions().get(task.region());
      if (expansion == null || expansion.state() != Expansion.State.QUEUED) {
        return;
      }
      model = current.withExpansion(task.region(), expansion.running(clock.instant()));
      explorations.put(task.key(), exploration.withModel(model));
    } finally {
      lock.unlock();
    }
    try {
      Region before = model.epoch().space().region(task.region()).orElseThrow();
      Epoch expanded = epochs.expand(model.epoch(), task.region());
      boolean kept = expanded.space().region(task.region()).isPresent();
      Map<Region.Id, Region> dropped = new HashMap<>(model.dropped());
      if (!kept) {
        dropped.put(task.region(), before);
      }
      Model computed = Model.of(epochs, expanded, Map.of(), dropped);
      Expansion.State state = kept ? Expansion.State.ADMITTED : Expansion.State.DROPPED;
      publish(
          task,
          live ->
              new Model(
                  computed.epoch(),
                  computed.prior(),
                  computed.breakdown(),
                  ended(live.expansions(), task.region(), state),
                  computed.dropped()));
    } catch (RuntimeException | Error failure) {
      LOG.log(
          Level.WARNING,
          "generating the candidates of " + task.region().value() + " failed",
          failure);
      String reason = message(failure);
      publish(
          task,
          live ->
              live.withExpansion(
                  task.region(),
                  live.expansions().get(task.region()).failed(clock.instant(), reason)));
    }
  }

  private Map<Region.Id, Expansion> ended(
      Map<Region.Id, Expansion> expansions, Region.Id region, Expansion.State state) {
    Map<Region.Id, Expansion> changed = new HashMap<>(expansions);
    changed.put(region, expansions.get(region).ended(state, clock.instant()));
    return changed;
  }

  /** Applies {@code change} to the live model of {@code task}'s exploration, if still running. */
  private void publish(Task.Expand task, UnaryOperator<Model> change) {
    lock.lock();
    try {
      Exploration exploration = explorations.get(task.key());
      if (exploration == null || exploration.status() != Status.FINISHED) {
        return;
      }
      Model live = exploration.model().orElseThrow();
      Expansion expansion = live.expansions().get(task.region());
      if (expansion == null || expansion.state() != Expansion.State.RUNNING) {
        return;
      }
      explorations.put(task.key(), exploration.withModel(change.apply(live)));
    } finally {
      lock.unlock();
    }
  }

  private void update(Key key, UnaryOperator<Exploration> change) {
    lock.lock();
    try {
      Exploration exploration = explorations.get(key);
      if (exploration != null) {
        explorations.put(key, change.apply(exploration));
      }
    } finally {
      lock.unlock();
    }
  }

  private void forget() {
    long settled = explorations.values().stream().filter(e -> !e.active()).count();
    Iterator<Exploration> oldest = explorations.values().iterator();
    while (settled > capacity && oldest.hasNext()) {
      if (!oldest.next().active()) {
        oldest.remove();
        settled--;
      }
    }
  }

  private static String message(Throwable failure) {
    return failure.getMessage() == null ? failure.toString() : failure.getMessage();
  }
}
