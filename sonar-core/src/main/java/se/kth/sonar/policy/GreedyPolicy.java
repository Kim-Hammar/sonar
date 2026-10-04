package se.kth.sonar.policy;

import java.util.OptionalInt;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairState;

/**
 * Spends the budget where the belief is highest, verifying a fixed number of the most probable
 * region's candidates at a time; see {@link BestRegionPolicy} for the steps.
 */
public final class GreedyPolicy extends BestRegionPolicy {
  /** The number of candidates verified per batch unless a run chooses another. */
  public static final int DEFAULT_BATCH = 5;

  private final int batch;

  /** Creates a policy that verifies {@value #DEFAULT_BATCH} candidates per batch. */
  public GreedyPolicy() {
    this(DEFAULT_BATCH);
  }

  /** Creates a policy that verifies {@code batch} candidates per batch. */
  public GreedyPolicy(int batch) {
    if (batch < 1) {
      throw new IllegalArgumentException("batch must be at least 1: " + batch);
    }
    this.batch = batch;
  }

  @Override
  OptionalInt candidates(RepairState state, Distribution<Region.Id> posterior, Region.Id best) {
    return OptionalInt.of(batch);
  }
}
