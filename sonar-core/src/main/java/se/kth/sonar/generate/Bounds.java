package se.kth.sonar.generate;

/**
 * Limits that keep generation finite.
 *
 * @param maxOperators the most operators in one hypothesis
 * @param maxRouters the most routers one hypothesis changes
 * @param maxHypothesesPerRegion the most hypotheses generated for one region
 * @param maxValuesPerSlot the most candidate values drawn for one slot
 * @param maxClosureDepth the most rounds of relation completion after a seed
 */
public record Bounds(
    int maxOperators,
    int maxRouters,
    int maxHypothesesPerRegion,
    int maxValuesPerSlot,
    int maxClosureDepth) {

  /** Checks that every limit is positive. */
  public Bounds {
    if (maxOperators < 1
        || maxRouters < 1
        || maxHypothesesPerRegion < 1
        || maxValuesPerSlot < 1
        || maxClosureDepth < 1) {
      throw new IllegalArgumentException("every generation bound must be at least 1");
    }
  }

  /** Returns the bounds used unless a run configures others. */
  public static Bounds defaults() {
    return new Bounds(8, 2, 64, 16, 3);
  }
}
