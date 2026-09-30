package se.kth.sonar.belief;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;

/**
 * Updates beliefs by Bayes' rule, starting from the region prior.
 *
 * <p>An assessment of a partition gives each of its regions the likelihood ratio {@code P(region) /
 * P(none)}: the model's probability that the region holds the repair against its probability that
 * no region of the partition does. Regions of other partitions keep the neutral ratio one, so the
 * belief refines lazily where the search looks. A batch of candidates that fails verification
 * multiplies its region's belief by the share of the region's candidate mass that remains, which
 * never raises the belief. A near miss, a candidate that fixed a specification while breaking
 * another, shows that its object matters, and lends every region editing that object the bounded
 * factor {@code 1 + n / (n + 1)} for {@code n} near misses.
 */
public final class BeliefModel {
  private static final double MINIMUM_NONE = 1e-12;

  /** Returns the belief before any evidence, which is the prior. */
  public BeliefState initial(Distribution<Region.Id> prior, int epoch) {
    return BeliefState.of(prior, epoch);
  }

  /** Returns the belief after the decision model assessed {@code partition}. */
  public BeliefState afterAssessment(
      BeliefState belief, Partition partition, RegionAssessment assessment) {
    Map<Region.Id, Double> odds = new LinkedHashMap<>(belief.odds());
    double none = Math.max(MINIMUM_NONE, assessment.none());
    for (Region.Id region : partition.regions()) {
      odds.put(region, (1 - none) * assessment.regions().probability(region) / none);
    }
    return new BeliefState(
        belief.prior(),
        odds,
        belief.survival(),
        belief.evidence(),
        assessedWith(belief, partition),
        belief.epoch());
  }

  /** Returns the belief after {@code partition} was assessed without a usable answer. */
  public BeliefState afterNeutralAssessment(BeliefState belief, Partition partition) {
    return new BeliefState(
        belief.prior(),
        belief.odds(),
        belief.survival(),
        belief.evidence(),
        assessedWith(belief, partition),
        belief.epoch());
  }

  /**
   * Returns the belief after candidates of {@code region} holding {@code testedMass} of its ranked
   * candidate mass failed verification.
   */
  public BeliefState afterFailures(BeliefState belief, Region.Id region, double testedMass) {
    double tested = Math.min(1, Math.max(0, testedMass));
    Map<Region.Id, Double> survival = new LinkedHashMap<>(belief.survival());
    survival.merge(region, 1 - tested, (a, b) -> a * b);
    return new BeliefState(
        belief.prior(),
        belief.odds(),
        survival,
        belief.evidence(),
        belief.assessed(),
        belief.epoch());
  }

  /**
   * Returns the belief with the near-miss support of every region replaced: a region editing an
   * object with {@code n} verified near misses gets {@code 1 + n / (n + 1)}.
   */
  public BeliefState withNearMisses(BeliefState belief, Map<Region.Id, Integer> nearMisses) {
    Map<Region.Id, Double> evidence = new LinkedHashMap<>();
    nearMisses.forEach(
        (region, count) -> {
          if (count > 0) {
            evidence.put(region, 1 + count / (count + 1.0));
          }
        });
    return new BeliefState(
        belief.prior(),
        belief.odds(),
        belief.survival(),
        evidence,
        belief.assessed(),
        belief.epoch());
  }

  private static Set<Region.Id> assessedWith(BeliefState belief, Partition partition) {
    Set<Region.Id> assessed = new LinkedHashSet<>(belief.assessed());
    assessed.addAll(partition.regions());
    return assessed;
  }
}
