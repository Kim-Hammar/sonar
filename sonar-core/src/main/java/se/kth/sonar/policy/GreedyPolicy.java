package se.kth.sonar.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * Spends the budget where the belief is highest. It takes the most probable region and, in this
 * order: asks the decision model to assess the region's partition when the partition has several
 * regions and has not been assessed in this state; generates the region's candidates when they are
 * deferred; asks the model to rank them when there are more than one batch and they are unranked;
 * and otherwise verifies the first batch in rank order. It stops when the network is repaired, the
 * budget runs out, or no region is left.
 */
public final class GreedyPolicy implements SearchPolicy {
  private static final int DEFAULT_BATCH = 5;
  private static final int COMPARISON_REGIONS = 16;

  private final int batch;

  /** Creates a policy that verifies {@value #DEFAULT_BATCH} candidates per batch. */
  public GreedyPolicy() {
    this(DEFAULT_BATCH);
  }

  /** Creates a policy that verifies {@code batch} candidates per batch. */
  public GreedyPolicy(int batch) {
    if (batch < 1) {
      throw new IllegalArgumentException("batch must be at least 1: " + batch);
    }
    this.batch = batch;
  }

  @Override
  public Action next(RepairState state, SearchSpace space, BeliefState belief, Budget budget) {
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
    if (!(region.hypotheses() instanceof Region.Hypotheses.Admitted admitted)) {
      return new Action.Expand(best);
    }
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
