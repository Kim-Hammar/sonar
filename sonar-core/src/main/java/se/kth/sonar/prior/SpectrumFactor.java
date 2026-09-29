package se.kth.sonar.prior;

import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairState;

/**
 * Weights a region by how strongly the verifier's evidence implicates the routers it edits: the
 * Ochiai coefficient of each router over the traces of violated and satisfied specifications,
 * maximised over the region's routers.
 */
@Placeholder
public final class SpectrumFactor implements PriorFactor {
  @Override
  public double weight(Region region, RepairState state) {
    throw new NotImplemented("SpectrumFactor.weight");
  }
}
