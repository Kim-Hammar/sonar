package se.kth.sonar.spec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How a data plane measures up against a scenario's specifications.
 *
 * @param unresolved broken specifications that the data plane still violates
 * @param fixed broken specifications that the data plane satisfies
 * @param sideEffects intact specifications that the data plane violates
 */
public record Verdict(
    List<Specification> unresolved, List<Specification> fixed, List<Specification> sideEffects) {

  /** How one verdict differs from an earlier one. */
  public enum Change {
    /** Some specifications were fixed and none were broken. */
    IMPROVEMENT,
    /** Some specifications were fixed and others were broken. */
    MIXED,
    /** Nothing was fixed and some specifications were broken. */
    REGRESSION,
    /** Nothing was fixed or broken. */
    NO_EFFECT
  }

  /** Copies the lists. */
  public Verdict {
    unresolved = List.copyOf(unresolved);
    fixed = List.copyOf(fixed);
    sideEffects = List.copyOf(sideEffects);
  }

  /** Returns whether every specification holds as required. */
  public boolean solved() {
    return unresolved.isEmpty() && sideEffects.isEmpty();
  }

  /**
   * Returns whether this verdict leaves a strict subset of the specifications that {@code before}
   * left unresolved, without side effects.
   */
  public boolean improvesOn(Verdict before) {
    Set<Specification> earlier = new HashSet<>(before.unresolved);
    return sideEffects.isEmpty()
        && unresolved.size() < earlier.size()
        && earlier.containsAll(unresolved);
  }

  /** Classifies the change from this verdict to {@code after}. */
  public Change changeTo(Verdict after) {
    Set<Specification> nowUnresolved = new HashSet<>(after.unresolved);
    Set<Specification> earlierUnresolved = new HashSet<>(unresolved);
    Set<Specification> earlierSideEffects = new HashSet<>(sideEffects);
    boolean fixedSome = unresolved.stream().anyMatch(s -> !nowUnresolved.contains(s));
    boolean brokeSome =
        after.unresolved.stream().anyMatch(s -> !earlierUnresolved.contains(s))
            || after.sideEffects.stream().anyMatch(s -> !earlierSideEffects.contains(s));
    if (fixedSome) {
      return brokeSome ? Change.MIXED : Change.IMPROVEMENT;
    }
    return brokeSome ? Change.REGRESSION : Change.NO_EFFECT;
  }
}
