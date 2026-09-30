package se.kth.sonar.policy;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.belief.BeliefState;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.state.RepairState;

/** Chooses what the search does next. */
public interface SearchPolicy {
  /** Returns the next action in {@code state}. */
  Action next(RepairState state, SearchSpace space, BeliefState belief, Budget budget);

  /** A step of the search. */
  sealed interface Action {
    /**
     * Ask the decision model to assess a partition.
     *
     * @param partition the partition
     */
    record Assess(Partition.Id partition) implements Action {}

    /**
     * Generate the candidates of a region.
     *
     * @param region the region
     */
    record Expand(Region.Id region) implements Action {}

    /**
     * Ask the decision model to rank the candidates of a region.
     *
     * @param region the region
     */
    record Rank(Region.Id region) implements Action {}

    /**
     * Verify candidates of a region as one batch.
     *
     * @param region the region
     * @param hypotheses the candidates, in the order to merge their results
     */
    record Verify(Region.Id region, List<ContentHash> hypotheses) implements Action {
      /** Copies the candidates. */
      public Verify {
        Objects.requireNonNull(region, "region");
        hypotheses = List.copyOf(hypotheses);
      }
    }

    /**
     * Stop the run.
     *
     * @param reason why
     */
    record Stop(RepairResult.Stop reason) implements Action {}
  }
}
