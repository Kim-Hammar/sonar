package se.kth.sonar.execution;

/**
 * How much of the pipeline may run at the same time. The results of a run never depend on these
 * values; only its duration does.
 *
 * @param verificationWorkers data-plane engines that verify candidates at the same time
 * @param decisionConcurrency decision-model requests in flight at the same time
 * @param generationParallelism threads that generate and admit candidates
 * @param scenarioParallelism scenarios that a benchmark run repairs at the same time
 * @param prefetchDecisions decisions requested ahead of need while a batch verifies
 */
public record ParallelismConfig(
    int verificationWorkers,
    int decisionConcurrency,
    int generationParallelism,
    int scenarioParallelism,
    int prefetchDecisions) {

  /** Checks that every limit is positive and the prefetch depth is not negative. */
  public ParallelismConfig {
    positive("verificationWorkers", verificationWorkers);
    positive("decisionConcurrency", decisionConcurrency);
    positive("generationParallelism", generationParallelism);
    positive("scenarioParallelism", scenarioParallelism);
    if (prefetchDecisions < 0) {
      throw new IllegalArgumentException("prefetchDecisions must not be negative");
    }
  }

  /** Returns the configuration that runs every stage on the calling thread. */
  public static ParallelismConfig sequential() {
    return new ParallelismConfig(1, 1, 1, 1, 0);
  }

  private static void positive(String name, int value) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be at least 1: " + value);
    }
  }
}
