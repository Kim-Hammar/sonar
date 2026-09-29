package se.kth.sonar.belief;

import java.util.Objects;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.search.Region;

/**
 * What the search believes about where the repair is.
 *
 * @param posterior the probability that each region holds a repair
 * @param epoch the state the belief refers to
 */
public record BeliefState(Distribution<Region.Id> posterior, int epoch) {
  /** Checks the components. */
  public BeliefState {
    Objects.requireNonNull(posterior, "posterior");
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch must not be negative: " + epoch);
    }
  }
}
