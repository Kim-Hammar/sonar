package se.kth.sonar.belief;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;

/**
 * What the search believes about where the repair is, kept as the factors that Bayes' rule
 * multiplies: the prior, the odds the decision model gave each assessed region against the chance
 * that its partition holds no repair, the share of each region's candidates not yet ruled out, and
 * the support that verified near misses lend the region's objects. A region absent from a factor
 * has the neutral value one.
 *
 * @param prior the prior over the regions
 * @param odds the decision model's odds for each assessed region
 * @param survival the share of each region's candidate mass that is not yet ruled out
 * @param evidence the support from near misses for each region
 * @param assessed the partitions the decision model has assessed
 * @param epoch the state the belief refers to
 */
public record BeliefState(
    Distribution<Region.Id> prior,
    Map<Region.Id, Double> odds,
    Map<Region.Id, Double> survival,
    Map<Region.Id, Double> evidence,
    Set<Partition.Id> assessed,
    int epoch) {

  /** Copies the collections and checks the components. */
  public BeliefState {
    Objects.requireNonNull(prior, "prior");
    odds = Map.copyOf(odds);
    survival = Map.copyOf(survival);
    evidence = Map.copyOf(evidence);
    assessed = Set.copyOf(assessed);
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch must not be negative: " + epoch);
    }
  }

  /** Returns the belief that is the prior alone. */
  public static BeliefState of(Distribution<Region.Id> prior, int epoch) {
    return new BeliefState(prior, Map.of(), Map.of(), Map.of(), Set.of(), epoch);
  }

  /** Returns the probability that each region holds a repair, in prior order. */
  public Distribution<Region.Id> posterior() {
    Map<Region.Id, Double> weights = new LinkedHashMap<>();
    for (Region.Id region : prior.outcomes()) {
      weights.put(region, weight(region));
    }
    return Distribution.of(weights);
  }

  /** Returns the unnormalised posterior weight of {@code region}. */
  public double weight(Region.Id region) {
    return prior.probability(region)
        * odds.getOrDefault(region, 1.0)
        * survival.getOrDefault(region, 1.0)
        * evidence.getOrDefault(region, 1.0);
  }

  /** Returns whether {@code partition} has been assessed. */
  public boolean isAssessed(Partition.Id partition) {
    return assessed.contains(partition);
  }

  /** Returns this belief without {@code regions}, which keeps the ratios of the others. */
  public BeliefState without(Collection<Region.Id> regions) {
    Set<Region.Id> gone = Set.copyOf(regions);
    if (gone.isEmpty() || prior.outcomes().stream().noneMatch(gone::contains)) {
      return this;
    }
    return new BeliefState(
        prior.without(gone),
        drop(odds, gone),
        drop(survival, gone),
        drop(evidence, gone),
        assessed,
        epoch);
  }

  /** Returns this belief with the prior {@code prior}, keeping every other factor. */
  public BeliefState withPrior(Distribution<Region.Id> prior) {
    return new BeliefState(prior, odds, survival, evidence, assessed, epoch);
  }

  private static Map<Region.Id, Double> drop(Map<Region.Id, Double> map, Set<Region.Id> gone) {
    Map<Region.Id, Double> kept = new LinkedHashMap<>(map);
    kept.keySet().removeAll(gone);
    return kept;
  }
}
