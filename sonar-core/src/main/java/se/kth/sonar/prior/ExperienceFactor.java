package se.kth.sonar.prior;

import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairState;

/**
 * Weights a region by what earlier runs learned: the odds, from a tally per relation and
 * production, that regions of its kind held a repair.
 */
@Placeholder
public final class ExperienceFactor implements PriorFactor {
  @Override
  public double weight(Region region, RepairState state) {
    throw new NotImplemented("ExperienceFactor.weight");
  }
}
