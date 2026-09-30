package se.kth.sonar.decision;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.Distribution;

/**
 * The decision model's order of a region's candidates, most promising first.
 *
 * @param order the candidate identifiers, each at most once
 * @param probabilities the probability that each candidate is the repair, given that one is
 */
public record HypothesisRanking(List<ContentHash> order, Distribution<ContentHash> probabilities) {
  /** Copies the order and checks that it lists exactly the candidates with probabilities. */
  public HypothesisRanking {
    order = List.copyOf(order);
    Objects.requireNonNull(probabilities, "probabilities");
    if (!probabilities.outcomes().equals(java.util.Set.copyOf(order))) {
      throw new IllegalArgumentException("the order must list exactly the candidates ranked");
    }
  }

  /** Ranks candidates by {@code probabilities}, most probable first. */
  public static HypothesisRanking by(Distribution<ContentHash> probabilities) {
    return new HypothesisRanking(probabilities.ranked(), probabilities);
  }
}
