package se.kth.sonar.app;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.policy.GreedyPolicy;
import se.kth.sonar.policy.KoopmanPolicy;
import se.kth.sonar.policy.SearchPolicy;

/**
 * Everything a user may choose about a run. Budgets apply to each scenario separately.
 *
 * @param specificationPercent the share of specifications to use, in per cent
 * @param verificationBatch the candidates the greedy policy verifies at a time, and the fewest the
 *     Koopman-index policy does
 * @param parallelism how much of the pipeline may run at the same time
 * @param limits the per-scenario limit of each limited resource
 * @param timeLimit the per-scenario time limit, if any
 * @param seed the seed of every random choice
 * @param koopman the parameters of the Koopman-index policy, or empty for the greedy policy
 * @param uniformPrior whether every region gets the same prior weight, without evidence factors or
 *     experience (an ablation)
 * @param regionAssessment whether the decision model assesses regions; without it (an ablation) the
 *     belief learns from verification alone and the model only ranks candidates
 * @param localization whether regions are built only on the routers the verifier's evidence
 *     implicates; without it (an ablation) they are built on every router
 * @param eta the weight {@code η ∈ [0, 1]} of the decision model's assessments in the belief
 */
public record RunSettings(
    int specificationPercent,
    int verificationBatch,
    ParallelismConfig parallelism,
    Map<Budget.Resource, Long> limits,
    Optional<Duration> timeLimit,
    long seed,
    Optional<KoopmanPolicy.Parameters> koopman,
    boolean uniformPrior,
    boolean regionAssessment,
    boolean localization,
    double eta) {

  /**
   * Checks the settings.
   *
   * @throws IllegalArgumentException if the percentage is outside 1 to 100, the batch is empty, a
   *     limit is negative, or the time limit is not positive
   */
  public RunSettings {
    if (specificationPercent < 1 || specificationPercent > 100) {
      throw new IllegalArgumentException("the specification percentage must lie in [1, 100]");
    }
    if (verificationBatch < 1) {
      throw new IllegalArgumentException("the verification batch must hold at least 1 candidate");
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
    Objects.requireNonNull(koopman, "koopman");
    if (!(eta >= 0 && eta <= 1)) {
      throw new IllegalArgumentException("η must lie in [0, 1]");
    }
  }

  /** Creates settings for the greedy policy. */
  public RunSettings(
      int specificationPercent,
      int verificationBatch,
      ParallelismConfig parallelism,
      Map<Budget.Resource, Long> limits,
      Optional<Duration> timeLimit,
      long seed) {
    this(
        specificationPercent,
        verificationBatch,
        parallelism,
        limits,
        timeLimit,
        seed,
        Optional.empty(),
        false,
        true,
        true,
        1);
  }

  /** Returns the search policy these settings choose. */
  public SearchPolicy policy() {
    return koopman
        .<SearchPolicy>map(
            parameters -> new KoopmanPolicy(parameters, verificationBatch, regionAssessment))
        .orElseGet(() -> new GreedyPolicy(verificationBatch, regionAssessment));
  }

  /** Returns the settings used unless the user chooses others. */
  public static RunSettings defaults() {
    return new RunSettings(
        10,
        GreedyPolicy.DEFAULT_BATCH,
        new ParallelismConfig(1, 4, Runtime.getRuntime().availableProcessors(), 1),
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
