package se.kth.sonar.app;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.policy.GreedyPolicy;
import se.kth.sonar.policy.KoopmanPolicy;

/**
 * The contents of a run's {@code run.json}: what was asked for and where the run stands. Absent
 * values are null so that the document maps directly to JSON.
 *
 * @param id the run's identifier
 * @param name the run's display name
 * @param demo whether the scripted repairer ran instead of the engine
 * @param status the status label
 * @param created when the run was created, in epoch milliseconds
 * @param started when the run started, or null
 * @param finished when the run ended, or null
 * @param error why the run failed, or null
 * @param scenarios the scenarios to repair, in order
 * @param settings the run settings
 * @param model the decision model the run asked
 */
public record RunDocument(
    String id,
    String name,
    boolean demo,
    String status,
    long created,
    Long started,
    Long finished,
    String error,
    List<String> scenarios,
    Settings settings,
    String model) {

  /** Copies the scenarios. */
  public RunDocument {
    scenarios = List.copyOf(scenarios);
  }

  /** Returns the status. */
  public RunStatus runStatus() {
    return RunStatus.parse(status);
  }

  /** Returns this document with {@code status}, marking the start or end time as it applies. */
  public RunDocument withStatus(RunStatus next, long at, String reason) {
    Long start = next == RunStatus.RUNNING ? Long.valueOf(at) : started;
    Long end = next.terminal() ? Long.valueOf(at) : finished;
    return new RunDocument(
        id, name, demo, next.label(), created, start, end, reason, scenarios, settings, model);
  }

  /**
   * The run settings as stored.
   *
   * @param specificationPercent the share of specifications, in per cent
   * @param verificationBatch the candidates verified at a time, or null for the default
   * @param verificationWorkers data-plane engines verifying at the same time
   * @param decisionConcurrency decision requests in flight at the same time
   * @param generationParallelism threads generating candidates
   * @param scenarioParallelism scenarios repaired at the same time
   * @param limits per-scenario limits keyed by lower-case resource name
   * @param timeLimitSeconds the per-scenario time limit in seconds, or null
   * @param seed the seed of every random choice
   * @param koopman the Koopman-index policy's parameters, or null for the greedy policy
   * @param uniformPrior whether the prior is uniform, or null for the informed prior
   * @param regionAssessment whether the decision model assesses regions, or null if it does
   * @param localization whether regions are localized by the evidence, or null if they are
   */
  public record Settings(
      int specificationPercent,
      Integer verificationBatch,
      int verificationWorkers,
      int decisionConcurrency,
      int generationParallelism,
      int scenarioParallelism,
      Map<String, Long> limits,
      Long timeLimitSeconds,
      long seed,
      KoopmanPolicy.Parameters koopman,
      Boolean uniformPrior,
      Boolean regionAssessment,
      Boolean localization) {

    /** Copies the limits. */
    public Settings {
      limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    /** Returns the stored form of {@code settings}. */
    public static Settings of(RunSettings settings) {
      Map<String, Long> limits = new TreeMap<>();
      settings
          .limits()
          .forEach(
              (resource, limit) -> limits.put(resource.name().toLowerCase(Locale.ROOT), limit));
      ParallelismConfig parallelism = settings.parallelism();
      return new Settings(
          settings.specificationPercent(),
          settings.verificationBatch(),
          parallelism.verificationWorkers(),
          parallelism.decisionConcurrency(),
          parallelism.generationParallelism(),
          parallelism.scenarioParallelism(),
          limits,
          settings.timeLimit().map(Duration::toSeconds).orElse(null),
          settings.seed(),
          settings.koopman().orElse(null),
          settings.uniformPrior() ? Boolean.TRUE : null,
          settings.regionAssessment() ? null : Boolean.FALSE,
          settings.localization() ? null : Boolean.FALSE);
    }

    /**
     * Returns the run settings this stored form describes.
     *
     * @throws IllegalArgumentException if a value is invalid or a resource is unknown
     */
    public RunSettings toRunSettings() {
      EnumMap<Budget.Resource, Long> parsed = new EnumMap<>(Budget.Resource.class);
      limits.forEach(
          (resource, limit) ->
              parsed.put(Budget.Resource.valueOf(resource.toUpperCase(Locale.ROOT)), limit));
      return new RunSettings(
          specificationPercent,
          verificationBatch == null ? GreedyPolicy.DEFAULT_BATCH : verificationBatch,
          new ParallelismConfig(
              verificationWorkers, decisionConcurrency, generationParallelism, scenarioParallelism),
          parsed,
          Optional.ofNullable(timeLimitSeconds).map(Duration::ofSeconds),
          seed,
          Optional.ofNullable(koopman),
          Boolean.TRUE.equals(uniformPrior),
          !Boolean.FALSE.equals(regionAssessment),
          !Boolean.FALSE.equals(localization));
    }
  }
}
