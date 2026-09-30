package se.kth.sonar.prior;

import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairState;

/** One factor of the prior over regions; the factors of a prior multiply. */
public interface PriorFactor {
  /** Returns the factor's name, which distinguishes it in a prior's breakdown. */
  default String name() {
    return getClass().getSimpleName();
  }

  /** Returns a non-negative, finite weight for {@code region} in {@code state}. */
  double weight(Region region, RepairState state);
}
