package se.kth.sonar.app;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.ParallelismConfig;

/**
 * Everything a user may choose about a run. Budgets apply to each scenario separately.
 *
 * @param specificationPercent the share of specifications to use, in per cent
 * @param parallelism how much of the pipeline may run at the same time
 * @param limits the per-scenario limit of each limited resource
 * @param timeLimit the per-scenario time limit, if any
 * @param seed the seed of every random choice
 */
public record RunSettings(
    int specificationPercent,
    ParallelismConfig parallelism,
    Map<Budget.Resource, Long> limits,
    Optional<Duration> timeLimit,
    long seed) {

  /**
   * Checks the settings.
   *
   * @throws IllegalArgumentException if the percentage is outside 1 to 100, a limit is negative, or
   *     the time limit is not positive
   */
  public RunSettings {
    if (specificationPercent < 1 || specificationPercent > 100) {
      throw new IllegalArgumentException("the specification percentage must lie in [1, 100]");
    }
    Objects.requireNonNull(parallelism, "parallelism");
    EnumMap<Budget.Resource, Long> copy = new EnumMap<>(Budget.Resource.class);
    limits.forEach(
        (resource, limit) -> {
          if (limit < 0) {
            throw new IllegalArgumentException(
                "the limit of " + resource + " must not be negative");
          }
          copy.put(resource, limit);
        });
    limits = Map.copyOf(copy);
    Objects.requireNonNull(timeLimit, "timeLimit");
    if (timeLimit.filter(limit -> limit.isNegative() || limit.isZero()).isPresent()) {
      throw new IllegalArgumentException("the time limit must be positive");
    }
  }

  /** Returns the settings used unless the user chooses others. */
  public static RunSettings defaults() {
    return new RunSettings(
        10,
        new ParallelismConfig(1, 4, Runtime.getRuntime().availableProcessors(), 1, 0),
        Map.of(),
        Optional.empty(),
        0);
  }

  /** Returns a fresh budget with these limits, for one scenario. */
  public Budget newBudget() {
    Budget.Builder builder = Budget.builder();
    limits.forEach(builder::limit);
    timeLimit.ifPresent(builder::timeLimit);
    return builder.build();
  }
}
