package se.kth.sonar.belief;

import se.kth.sonar.common.Distribution;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;

/**
 * Updates beliefs by Bayes' rule, starting from the region prior.
 *
 * <p>An assessment redistributes the belief within its partition according to the model's
 * probabilities and scales the partition's total by the odds that one of its regions holds a
 * repair. Candidates that fail verification lower their region's belief by the share of the
 * region's candidates they rule out.
 */
@Placeholder
public final class BeliefModel {
  /** Returns the belief before any evidence, which is the prior. */
  public BeliefState initial(Distribution<Region.Id> prior, int epoch) {
    throw new NotImplemented("BeliefModel.initial");
  }

  /** Returns the belief after the decision model assessed {@code partition}. */
  public BeliefState afterAssessment(
      BeliefState belief, Partition partition, RegionAssessment assessment) {
    throw new NotImplemented("BeliefModel.afterAssessment");
  }

  /** Returns the belief after {@code failed} candidates of {@code region} failed verification. */
  public BeliefState afterFailures(BeliefState belief, Region region, int failed) {
    throw new NotImplemented("BeliefModel.afterFailures");
  }
}
