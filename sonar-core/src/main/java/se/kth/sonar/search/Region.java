package se.kth.sonar.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

/**
 * A part of the search space: the candidate repairs at one location, judged together.
 *
 * <p>A region is immutable. Its hypotheses are generated only when the search needs them, and the
 * expanded region replaces the deferred one in the search space.
 */
public sealed interface Region
    permits SiteRegion, RelationRegion, IncidenceRegion, MeshRegion, TransferRegion {
  /** Returns the region's identifier, unique within a search space. */
  Id id();

  /** Returns the violated specifications the region is expected to repair. */
  List<Specification> targets();

  /** Returns the digest of every configuration the region was derived from. */
  Map<RouterId, ContentHash> reads();

  /** Returns the region's candidates, or a bound on their number if they are not generated yet. */
  Hypotheses hypotheses();

  /** Returns the routers the region's candidates may change. */
  NavigableSet<RouterId> routers();

  /** Returns this region with generated candidates. */
  Region withHypotheses(Hypotheses.Admitted admitted);

  /** Returns the signature of the targets; regions with equal signatures compete. */
  default ContentHash signature() {
    return signatureOf(targets());
  }

  /** Returns the number of candidates, or a bound on it if they are not generated yet. */
  default int bound() {
    return hypotheses().bound();
  }

  /** Returns whether every configuration the region read is unchanged in {@code snapshot}. */
  default boolean isCurrent(Snapshot snapshot) {
    return reads().entrySet().stream()
        .allMatch(
            read ->
                snapshot.contains(read.getKey())
                    && snapshot.text(read.getKey()).digest().equals(read.getValue()));
  }

  /** Returns the signature of a set of targets, independent of their order. */
  static ContentHash signatureOf(Collection<Specification> targets) {
    ContentHash.Builder digest = ContentHash.builder();
    targets.stream()
        .map(target -> target.predicate().key())
        .distinct()
        .sorted()
        .forEach(digest::add);
    return digest.build();
  }

  /**
   * Identifies a region.
   *
   * @param value the identifier text
   */
  record Id(String value) implements Comparable<Id> {
    /** Checks that the identifier is not empty. */
    public Id {
      if (value.isEmpty()) {
        throw new IllegalArgumentException("a region identifier is not empty");
      }
    }

    @Override
    public int compareTo(Id other) {
      return value.compareTo(other.value);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** The candidates of a region. */
  sealed interface Hypotheses {
    /** Returns the number of candidates, or a bound on it. */
    int bound();

    /**
     * Candidates that are not generated yet.
     *
     * @param bound the most candidates generation will produce
     */
    record Deferred(int bound) implements Hypotheses {
      /** Checks the bound. */
      public Deferred {
        if (bound < 0) {
          throw new IllegalArgumentException("bound must not be negative: " + bound);
        }
      }
    }

    /**
     * Generated candidates that passed admissibility, in generation order until they are ranked,
     * and then most promising first.
     *
     * @param list the candidates
     * @param ranking the probability that each candidate is the repair, once the candidates are
     *     ranked
     * @param generated the candidates as they were before any was removed, if any was
     */
    record Admitted(
        List<Hypothesis> list,
        Optional<Distribution<ContentHash>> ranking,
        Optional<Admitted> generated)
        implements Hypotheses {
      /** Copies the list and checks that a ranking covers exactly the candidates. */
      public Admitted {
        list = List.copyOf(Objects.requireNonNull(list, "list"));
        Objects.requireNonNull(ranking, "ranking");
        Objects.requireNonNull(generated, "generated");
        if (ranking.isPresent()) {
          Set<ContentHash> ids = new HashSet<>();
          list.forEach(hypothesis -> ids.add(hypothesis.id()));
          if (!ranking.get().outcomes().equals(ids)) {
            throw new IllegalArgumentException("the ranking must cover exactly the candidates");
          }
        }
      }

      /** Unranked candidates. */
      public Admitted(List<Hypothesis> list) {
        this(list, Optional.empty());
      }

      /** Candidates of which none has been removed. */
      public Admitted(List<Hypothesis> list, Optional<Distribution<ContentHash>> ranking) {
        this(list, ranking, Optional.empty());
      }

      /**
       * Returns the candidates as they were before any was removed. What was tried and set aside in
       * one state of the network may repair another, so a region carried into a new state starts
       * from all of its candidates again.
       */
      public Admitted restored() {
        return generated.orElse(this);
      }

      @Override
      public int bound() {
        return list.size();
      }

      /** Returns whether the candidates are ranked. */
      public boolean ranked() {
        return ranking.isPresent();
      }

      /**
       * Returns the candidates in ranking order, with their probabilities. Among the candidates the
       * ranking gives a chance, one that needs an invented value comes after those built from
       * values the network or the verifier already has, whatever their probabilities: an invention
       * is a last resort. Candidates the ranking gives no chance keep their place at the end.
       */
      public Admitted rankedBy(Distribution<ContentHash> probabilities) {
        Map<ContentHash, Hypothesis> byId = new HashMap<>();
        list.forEach(hypothesis -> byId.put(hypothesis.id(), hypothesis));
        List<Hypothesis> ranked = new ArrayList<>();
        for (ContentHash id : probabilities.ranked()) {
          Hypothesis hypothesis = byId.remove(id);
          if (hypothesis != null) {
            ranked.add(hypothesis);
          }
        }
        for (Hypothesis hypothesis : list) {
          if (byId.containsKey(hypothesis.id())) {
            ranked.add(hypothesis);
          }
        }
        List<Hypothesis> ordered = new ArrayList<>();
        for (boolean invented : List.of(false, true)) {
          for (Hypothesis hypothesis : ranked) {
            if (probabilities.probability(hypothesis.id()) > 0
                && (hypothesis.support() == ValueSupport.INVENTED) == invented) {
              ordered.add(hypothesis);
            }
          }
        }
        for (Hypothesis hypothesis : ranked) {
          if (probabilities.probability(hypothesis.id()) <= 0) {
            ordered.add(hypothesis);
          }
        }
        Map<ContentHash, Double> weights = new LinkedHashMap<>();
        ordered.forEach(
            hypothesis -> weights.put(hypothesis.id(), probabilities.probability(hypothesis.id())));
        return new Admitted(ordered, Optional.of(Distribution.of(weights)), generated);
      }

      /** Returns the candidates other than {@code removed}, keeping the ranking of the rest. */
      public Admitted without(Collection<ContentHash> removed) {
        Set<ContentHash> gone = Set.copyOf(removed);
        List<Hypothesis> kept = list.stream().filter(h -> !gone.contains(h.id())).toList();
        Optional<Admitted> before = Optional.of(restored());
        if (kept.isEmpty() || ranking.isEmpty()) {
          return new Admitted(kept, Optional.empty(), before);
        }
        return new Admitted(kept, Optional.of(ranking.get().without(gone)), before);
      }
    }
  }
}
