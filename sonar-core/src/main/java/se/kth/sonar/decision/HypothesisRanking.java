package se.kth.sonar.decision;

import java.util.HashSet;
import java.util.List;
import se.kth.sonar.common.ContentHash;

/**
 * The decision model's order of a region's candidates, most promising first.
 *
 * @param order the candidate identifiers, each at most once
 */
public record HypothesisRanking(List<ContentHash> order) {
  /** Copies the order and checks that it has no repeats. */
  public HypothesisRanking {
    order = List.copyOf(order);
    if (new HashSet<>(order).size() != order.size()) {
      throw new IllegalArgumentException("a ranking names each candidate at most once");
    }
  }
}
