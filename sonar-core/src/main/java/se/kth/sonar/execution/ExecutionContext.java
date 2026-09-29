package se.kth.sonar.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Runs the stages of the pipeline concurrently and returns their results in input order.
 *
 * <p>Blocking work, such as data-plane analyses, decision requests and file loading, runs on
 * virtual threads. CPU-bound work, such as generation, admissibility checks and priors, runs on a
 * dedicated fork-join pool. Every task sees the scoped values that were bound on the submitting
 * thread for the keys given when the context was opened. A limit of one runs the work on the
 * calling thread, which makes the sequential configuration easy to debug.
 */
public final class ExecutionContext implements AutoCloseable {
  private final ParallelismConfig config;
  private final List<ScopedValue<?>> propagated;
  private final Optional<ForkJoinPool> computePool;
  private final ExecutorService background;
  private final AtomicBoolean closed = new AtomicBoolean();

  private ExecutionContext(ParallelismConfig config, List<ScopedValue<?>> propagated) {
    this.config = config;
    this.propagated = propagated;
    this.computePool =
        config.generationParallelism() > 1
            ? Optional.of(new ForkJoinPool(config.generationParallelism()))
            : Optional.empty();
    this.background =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sonar-async-", 0).factory());
  }

  /** Opens a context that propagates the scoped values {@code propagated} to its tasks. */
  public static ExecutionContext open(
      ParallelismConfig config, List<? extends ScopedValue<?>> propagated) {
    return new ExecutionContext(Objects.requireNonNull(config, "config"), List.copyOf(propagated));
  }

  /** Returns the limits this context was opened with. */
  public ParallelismConfig config() {
    return config;
  }

  /**
   * Applies a blocking function to every input, with at most {@code limit} calls in flight, and
   * returns the results in input order. See {@link FanOut} for how failures are reported.
   */
  public <T, R> List<R> fanOut(
      List<? extends T> inputs, int limit, Function<? super T, ? extends R> function) {
    ensureOpen();
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1: " + limit);
    }
    if (limit == 1 || inputs.size() < 2) {
      return FanOut.sequential(inputs, function);
    }
    return FanOut.concurrent(inputs, limit, function, FanOut.capture(propagated));
  }

  /**
   * Applies a CPU-bound function to every input on the compute pool and returns the results in
   * input order.
   */
  public <T, R> List<R> compute(
      List<? extends T> inputs, Function<? super T, ? extends R> function) {
    ensureOpen();
    if (computePool.isEmpty() || inputs.size() < 2) {
      return FanOut.sequential(inputs, function);
    }
    Optional<ScopedValue.Carrier> bindings = FanOut.capture(propagated);
    List<ForkJoinTask<FanOut.Outcome<R>>> tasks = new ArrayList<>(inputs.size());
    for (T input : inputs) {
      tasks.add(computePool.get().submit(() -> FanOut.attempt(input, function, bindings)));
    }
    List<FanOut.Outcome<R>> outcomes = new ArrayList<>(tasks.size());
    for (ForkJoinTask<FanOut.Outcome<R>> task : tasks) {
      outcomes.add(task.join());
    }
    return FanOut.unwrap(outcomes);
  }

  /** Starts a blocking task on a virtual thread, for work that overlaps with the caller. */
  public <R> Future<R> async(Callable<? extends R> task) {
    ensureOpen();
    Optional<ScopedValue.Carrier> bindings = FanOut.capture(propagated);
    Callable<R> bound = () -> bindings.isPresent() ? bindings.get().call(task::call) : task.call();
    return background.submit(bound);
  }

  /** Cancels unfinished asynchronous tasks, waits for them to stop, and releases the threads. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    background.shutdownNow();
    background.close();
    computePool.ifPresent(ForkJoinPool::close);
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("the execution context is closed");
    }
  }
}
