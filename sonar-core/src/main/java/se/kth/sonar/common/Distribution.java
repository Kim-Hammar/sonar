package se.kth.sonar.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;

/**
 * A probability distribution over finitely many distinct outcomes.
 *
 * <p>Outcomes keep the order in which they were given, which breaks ties deterministically.
 *
 * @param <T> the outcome type
 */
public final class Distribution<T> {
  private final SequencedMap<T, Double> probabilities;

  private Distribution(SequencedMap<T, Double> probabilities) {
    this.probabilities = Collections.unmodifiableSequencedMap(probabilities);
  }

  /**
   * Normalises non-negative weights into a distribution. When every weight is zero the result is
   * uniform, which is the least informative choice.
   *
   * @throws IllegalArgumentException if there is no outcome or a weight is negative or not finite
   */
  public static <T> Distribution<T> of(Map<T, Double> weights) {
    if (weights.isEmpty()) {
      throw new IllegalArgumentException("a distribution needs at least one outcome");
    }
    double total = 0;
    for (Map.Entry<T, Double> entry : weights.entrySet()) {
      double weight = entry.getValue();
      if (!Double.isFinite(weight) || weight < 0) {
        throw new IllegalArgumentException("invalid weight " + weight + " for " + entry.getKey());
      }
      total += weight;
    }
    SequencedMap<T, Double> normalised = new LinkedHashMap<>();
    for (Map.Entry<T, Double> entry : weights.entrySet()) {
      normalised.put(entry.getKey(), total > 0 ? entry.getValue() / total : 1.0 / weights.size());
    }
    return new Distribution<>(normalised);
  }

  /** Returns the uniform distribution over {@code outcomes}, which must be distinct. */
  public static <T> Distribution<T> uniform(Collection<? extends T> outcomes) {
    SequencedMap<T, Double> weights = new LinkedHashMap<>();
    for (T outcome : outcomes) {
      if (weights.put(outcome, 1.0) != null) {
        throw new IllegalArgumentException("duplicate outcome " + outcome);
      }
    }
    return of(weights);
  }

  /** Returns the probability of {@code outcome}, which is zero for an unknown outcome. */
  public double probability(T outcome) {
    return probabilities.getOrDefault(outcome, 0.0);
  }

  /** Returns every outcome in the original order. */
  public SequencedSet<T> outcomes() {
    return Collections.unmodifiableSequencedSet(new LinkedHashSet<>(probabilities.keySet()));
  }

  /** Returns the outcomes that have positive probability, in the original order. */
  public SequencedSet<T> support() {
    SequencedSet<T> support = new LinkedHashSet<>();
    probabilities.forEach(
        (outcome, p) -> {
          if (p > 0) {
            support.add(outcome);
          }
        });
    return Collections.unmodifiableSequencedSet(support);
  }

  /** Returns the total probability of the distinct outcomes in {@code outcomes}. */
  public double mass(Collection<? extends T> outcomes) {
    double mass = 0;
    for (T outcome : new HashSet<T>(outcomes)) {
      mass += probability(outcome);
    }
    return mass;
  }

  /**
   * Conditions the distribution on the outcomes not in {@code excluded}.
   *
   * @throws IllegalArgumentException if no outcome remains
   */
  public Distribution<T> without(Collection<? extends T> excluded) {
    Set<T> removed = new HashSet<>(excluded);
    SequencedMap<T, Double> remaining = new LinkedHashMap<>();
    probabilities.forEach(
        (outcome, p) -> {
          if (!removed.contains(outcome)) {
            remaining.put(outcome, p);
          }
        });
    return of(remaining);
  }

  /**
   * Mixes the distribution with the uniform one: every outcome receives {@code epsilon} of the mass
   * evenly and keeps {@code 1 - epsilon} of its own probability.
   */
  public Distribution<T> smoothed(double epsilon) {
    if (!(epsilon >= 0 && epsilon <= 1)) {
      throw new IllegalArgumentException("epsilon must lie in [0, 1]: " + epsilon);
    }
    double share = epsilon / probabilities.size();
    SequencedMap<T, Double> mixed = new LinkedHashMap<>();
    probabilities.forEach((outcome, p) -> mixed.put(outcome, (1 - epsilon) * p + share));
    return of(mixed);
  }

  /**
   * Returns the outcomes from most to least probable; equal probabilities keep the original order.
   */
  public List<T> ranked() {
    List<T> ranked = new ArrayList<>(probabilities.keySet());
    ranked.sort(Comparator.comparingDouble((T outcome) -> probabilities.get(outcome)).reversed());
    return Collections.unmodifiableList(ranked);
  }

  /** Returns the most probable outcome; ties go to the earliest outcome. */
  public T argmax() {
    return ranked().getFirst();
  }

  /** Returns the number of outcomes. */
  public int size() {
    return probabilities.size();
  }

  /** Returns the probabilities by outcome, in the original order. */
  public SequencedMap<T, Double> asMap() {
    return probabilities;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Distribution<?> that && probabilities.equals(that.probabilities);
  }

  @Override
  public int hashCode() {
    return probabilities.hashCode();
  }

  @Override
  public String toString() {
    return probabilities.toString();
  }
}
