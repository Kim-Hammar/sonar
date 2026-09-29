package se.kth.sonar.prior;

import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairState;

/** One factor of the prior over regions; the factors of a prior multiply. */
public interface PriorFactor {
  /** Returns a non-negative, finite weight for {@code region} in {@code state}. */
  double weight(Region region, RepairState state);
}
