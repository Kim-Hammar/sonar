package se.kth.sonar.execution;

import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import se.kth.sonar.common.ContentHash;

/**
 * The scenario that the current thread works for: its budget, seed, and cancellation token. It is
 * bound with {@link #CURRENT} for the duration of the scenario, and {@link ExecutionContext}
 * propagates it to worker threads.
 *
 * @param scenario the scenario being repaired
 * @param budget the budget the scenario charges
 * @param seed the seed from which every random choice of the run derives
 * @param cancellation the token that asks the run to stop
 */
public record RunScope(String scenario, Budget budget, long seed, Cancellation cancellation) {
  /** The run scope of the current thread. */
  public static final ScopedValue<RunScope> CURRENT = ScopedValue.newInstance();

  /** Checks the components. */
  public RunScope {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(cancellation, "cancellation");
  }

  /** Returns the run scope bound to the current thread, if any. */
  public static Optional<RunScope> current() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
  }

  /**
   * Returns a random generator for {@code task}. The same seed and task give the same sequence on
   * every run, whichever thread executes the task.
   */
  public RandomGenerator random(String task) {
    return new SplittableRandom(ContentHash.builder().add(seed).add(task).build().asLong());
  }
}
