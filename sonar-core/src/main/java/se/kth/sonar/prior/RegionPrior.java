package se.kth.sonar.prior;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.state.RepairState;

/**
 * The prior belief about which region holds a repair: the normalised product of independent
 * factors. Without factors the prior is uniform.
 */
public final class RegionPrior {
  private final List<PriorFactor> factors;

  /** Creates a prior from {@code factors}. */
  public RegionPrior(List<? extends PriorFactor> factors) {
    this.factors = List.copyOf(factors);
  }

  /** Returns this prior with one more factor. */
  public RegionPrior with(PriorFactor factor) {
    List<PriorFactor> extended = new ArrayList<>(factors);
    extended.add(Objects.requireNonNull(factor, "factor"));
    return new RegionPrior(extended);
  }

  /** Returns the prior over the regions of {@code space}, in region order. */
  public Distribution<Region.Id> over(SearchSpace space, RepairState state) {
    Map<Region.Id, Double> weights = new LinkedHashMap<>();
    for (Region region : space.regions()) {
      weights.put(region.id(), weight(region, state));
    }
    return Distribution.of(weights);
  }

  /**
   * Returns the product of the factor weights of {@code region}.
   *
   * @throws IllegalStateException if a factor returns a negative or non-finite weight
   */
  public double weight(Region region, RepairState state) {
    double product = 1;
    for (PriorFactor factor : factors) {
      double weight = factor.weight(region, state);
      if (!Double.isFinite(weight) || weight < 0) {
        throw new IllegalStateException(factor + " gave weight " + weight + " to " + region.id());
      }
      product *= weight;
    }
    return product;
  }
}
