package se.kth.sonar.prior;

import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairState;

/**
 * Weights a region by the relation requirements its candidates restore: a region that completes a
 * violated hard requirement weighs more than one that completes a soft requirement, which weighs
 * more than a region that completes none.
 */
@Placeholder
public final class RelationFactor implements PriorFactor {
  @Override
  public double weight(Region region, RepairState state) {
    throw new NotImplemented("RelationFactor.weight");
  }
}
