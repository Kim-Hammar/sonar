package se.kth.sonar.decision;

import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.state.RepairState;

/**
 * Builds the questions the search asks, with the facts the decision model needs to answer them.
 *
 * <p>Sections come in a fixed order: the violated specifications, the verifier's evidence, the
 * relevant configuration excerpts, and the choices, each described by its edits and by the relation
 * requirements it restores. A question larger than the payload limit is reported as too large
 * rather than truncated.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class DecisionProblemFactory {
  private final int payloadLimit;

  /** Creates a factory for questions of at most {@code payloadLimit} characters. */
  public DecisionProblemFactory(int payloadLimit) {
    if (payloadLimit < 1) {
      throw new IllegalArgumentException("payload limit must be at least 1: " + payloadLimit);
    }
    this.payloadLimit = payloadLimit;
  }

  /** Returns the question of which region of {@code partition} holds the repair. */
  public DecisionProblem.RegionChoice regionChoice(
      RepairState state, SearchSpace space, Partition partition) {
    throw new NotImplemented("DecisionProblemFactory.regionChoice");
  }

  /** Returns the question of how to order the candidates of {@code region}. */
  public DecisionProblem.HypothesisChoice hypothesisChoice(RepairState state, Region region) {
    throw new NotImplemented("DecisionProblemFactory.hypothesisChoice");
  }
}
