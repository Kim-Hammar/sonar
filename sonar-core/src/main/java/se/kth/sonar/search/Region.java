package se.kth.sonar.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

/**
 * A part of the search space: the candidate repairs at one location, judged together.
 *
 * <p>A region is immutable. Its hypotheses are generated only when the search needs them, and the
 * expanded region replaces the deferred one in the search space.
 */
public sealed interface Region permits SiteRegion, RelationRegion {
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
     * Generated candidates that passed admissibility, in generation order.
     *
     * @param list the candidates
     */
    record Admitted(List<Hypothesis> list) implements Hypotheses {
      /** Copies the list. */
      public Admitted {
        list = List.copyOf(Objects.requireNonNull(list, "list"));
      }

      @Override
      public int bound() {
        return list.size();
      }
    }
  }
}
