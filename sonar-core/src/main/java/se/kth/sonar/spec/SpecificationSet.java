package se.kth.sonar.spec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;

/**
 * The specifications of one scenario, at most one per predicate.
 *
 * <p>When several rows name the same predicate, the row with the highest status precedence wins,
 * and the earliest such row wins a tie, as in the benchmark's evaluator.
 */
public final class SpecificationSet {
  private final Map<ForwardingPredicate, Specification> byPredicate;
  private final List<Specification> all;
  private final ContentHash id;

  private SpecificationSet(Map<ForwardingPredicate, Specification> byPredicate) {
    this.byPredicate = byPredicate;
    this.all = List.copyOf(byPredicate.values());
    ContentHash.Builder digest = ContentHash.builder();
    for (Specification specification : all) {
      digest.add(specification.predicate().key()).add(specification.status().label());
    }
    this.id = digest.build();
  }

  /** Resolves duplicate rows and returns the resulting set. */
  public static SpecificationSet of(Collection<Specification> rows) {
    Map<ForwardingPredicate, Specification> byPredicate = new LinkedHashMap<>();
    for (Specification row : rows) {
      Specification current = byPredicate.get(row.predicate());
      if (current == null || row.status().precedence() > current.status().precedence()) {
        byPredicate.put(row.predicate(), row);
      }
    }
    return new SpecificationSet(byPredicate);
  }

  /** Returns every specification in file order. */
  public List<Specification> all() {
    return all;
  }

  /** Returns the specifications that were broken in the faulty network. */
  public List<Specification> broken() {
    return all.stream()
        .filter(specification -> specification.status().initial() == Specification.Condition.BROKEN)
        .toList();
  }

  /** Returns the specification of {@code predicate}, if any. */
  public Optional<Specification> find(ForwardingPredicate predicate) {
    return Optional.ofNullable(byPredicate.get(predicate));
  }

  /** Returns the number of specifications. */
  public int size() {
    return all.size();
  }

  /** Returns the content identifier. */
  public ContentHash id() {
    return id;
  }

  /**
   * Judges a data plane in which exactly the predicates in {@code observed} hold. A broken
   * specification is fixed when the predicate's presence matches the expectation. An intact
   * specification whose presence no longer matches is a side effect. Specifications of unknown
   * status are skipped.
   */
  public Verdict judge(Set<ForwardingPredicate> observed) {
    List<Specification> unresolved = new ArrayList<>();
    List<Specification> fixed = new ArrayList<>();
    List<Specification> sideEffects = new ArrayList<>();
    for (Specification specification : all) {
      boolean matches =
          observed.contains(specification.predicate()) == specification.status().expectPresent();
      switch (specification.status().initial()) {
        case BROKEN -> (matches ? fixed : unresolved).add(specification);
        case INTACT -> {
          if (!matches) {
            sideEffects.add(specification);
          }
        }
        case UNKNOWN -> {}
      }
    }
    return new Verdict(unresolved, fixed, sideEffects);
  }
}
