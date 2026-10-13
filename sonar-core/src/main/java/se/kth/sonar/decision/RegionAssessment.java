package se.kth.sonar.decision;

import java.util.Objects;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.search.Region;

/**
 * The decision model's judgement of one partition.
 *
 * @param regions the probability that each region holds the repair, given that one does
 * @param none the probability that no region of the partition holds a repair
 */
public record RegionAssessment(Distribution<Region.Id> regions, double none) {
  /** Checks that {@code none} is a probability. */
  public RegionAssessment {
    Objects.requireNonNull(regions, "regions");
    if (!(none >= 0 && none <= 1)) {
      throw new IllegalArgumentException("none must lie in [0, 1]: " + none);
    }
  }
}
