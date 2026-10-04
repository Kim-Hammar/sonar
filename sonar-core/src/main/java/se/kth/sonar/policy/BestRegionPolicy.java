package se.kth.sonar.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import se.kth.sonar.belief.BeliefState;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.state.RepairState;

/**
 * Searches the most probable region. It stops when the network is repaired, the budget runs out, or
 * no region is left; otherwise, in this order, it asks the decision model to assess the best
 * region's partition when the partition has several regions and has not been assessed in this
 * state; asks the subclass how many candidates to verify, which may stop the search; generates the
 * region's candidates when they are deferred; asks the model to rank them when there are more than
 * it will verify and they are unranked; and otherwise verifies that many in rank order.
 */
abstract class BestRegionPolicy implements SearchPolicy {
  private static final int COMPARISON_REGIONS = 16;

  /**
   * Returns how many candidates of {@code best} to verify in this step, or nothing to stop
   * searching.
   *
   * @param state the current state
   * @param posterior the belief over the regions
   * @param best the most probable region
   */
  abstract OptionalInt candidates(
      RepairState state, Distribution<Region.Id> posterior, Region.Id best);

  @Override
  public final Action next(
      RepairState state, SearchSpace space, BeliefState belief, Budget budget) {
    if (state.solved()) {
      return new Action.Stop(new RepairResult.Stop.Solved());
    }
    if (budget.expired()) {
      return new Action.Stop(new RepairResult.Stop.TimeExpired());
    }
    if (budget.exhausted(Budget.Resource.VERIFICATIONS)) {
      return new Action.Stop(new RepairResult.Stop.BudgetExhausted(Budget.Resource.VERIFICATIONS));
    }
    if (space.isEmpty()) {
      return new Action.Stop(new RepairResult.Stop.SearchExhausted());
    }
    Distribution<Region.Id> posterior = belief.posterior();
    Region.Id best = posterior.argmax();
    Region region = space.region(best).orElseThrow();
    boolean canDecide = !budget.exhausted(Budget.Resource.DECISIONS);
    if (!belief.isAssessed(best) && canDecide) {
      List<Region.Id> competitors =
          posterior.outcomes().stream()
              .filter(id -> !belief.isAssessed(id))
              .sorted(
                  Comparator.<Region.Id>comparingDouble(posterior::probability)
                      .reversed()
                      .thenComparing(Region.Id::value))
              .limit(COMPARISON_REGIONS)
              .toList();
      if (competitors.size() > 1) {
        return new Action.Assess(Partition.comparison(competitors));
      }
    }
    OptionalInt candidates = candidates(state, posterior, best);
    if (candidates.isEmpty()) {
      return new Action.Stop(new RepairResult.Stop.Abandoned());
    }
    if (!(region.hypotheses() instanceof Region.Hypotheses.Admitted admitted)) {
      return new Action.Expand(best);
    }
    int batch = candidates.getAsInt();
    if (admitted.list().size() > batch && !admitted.ranked() && canDecide) {
      return new Action.Rank(best);
    }
    List<ContentHash> ids = new ArrayList<>();
    for (Hypothesis hypothesis : admitted.list().subList(0, Math.min(batch, admitted.bound()))) {
      ids.add(hypothesis.id());
    }
    return new Action.Verify(best, ids);
  }
}
