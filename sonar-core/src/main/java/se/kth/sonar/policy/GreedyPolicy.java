package se.kth.sonar.policy;

import se.kth.sonar.belief.BeliefState;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.state.RepairState;

/**
 * Spends the budget where the belief is highest: it assesses the partition with the most belief
 * that has not been assessed in this state, expands the most probable region, and verifies that
 * region's candidates in the order the decision model ranked them. It stops when the network is
 * repaired, the budget runs out, or no candidate is left.
 */
@Placeholder
public final class GreedyPolicy implements SearchPolicy {
  @Override
  public Action next(RepairState state, SearchSpace space, BeliefState belief, Budget budget) {
    throw new NotImplemented("GreedyPolicy.next");
  }
}
