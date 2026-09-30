package se.kth.sonar.engine;

import java.util.Objects;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.state.RepairState;

/**
 * Where a search stands between adoptions: the network under repair and the search space over it.
 *
 * @param state the network, its verification and its program
 * @param space the regions of candidate repairs
 */
public record Epoch(RepairState state, SearchSpace space) {
  /** Checks the components. */
  public Epoch {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(space, "space");
  }
}
