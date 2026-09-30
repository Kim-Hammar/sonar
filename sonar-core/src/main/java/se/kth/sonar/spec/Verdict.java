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
    return loss() == 0;
  }

  /** Returns the number of formal obligations that currently fail. */
  public int loss() {
    return failures().size();
  }

  /**
   * Returns whether this verdict strictly reduces formal loss without breaking another obligation
   * that was originally intact.
   *
   * <p>A repair can expose a previously masked benchmark fault. Requiring the new unresolved set to
   * be a subset of the old one rejects genuine progress in that case. Total formal loss is a
   * well-founded objective; requiring the side-effect set not to grow separately preserves the
   * contract for specifications that were designated intact.
   */
  public boolean improvesOn(Verdict before) {
    return loss() < before.loss() && new HashSet<>(before.sideEffects).containsAll(sideEffects);
  }

  /** Classifies the change from this verdict to {@code after}. */
  public Change changeTo(Verdict after) {
    Set<Specification> beforeFailures = failures();
    Set<Specification> afterFailures = after.failures();
    boolean fixedSome = beforeFailures.stream().anyMatch(s -> !afterFailures.contains(s));
    boolean brokeSome = afterFailures.stream().anyMatch(s -> !beforeFailures.contains(s));
    if (fixedSome) {
      return brokeSome ? Change.MIXED : Change.IMPROVEMENT;
    }
    return brokeSome ? Change.REGRESSION : Change.NO_EFFECT;
  }

  private Set<Specification> failures() {
    Set<Specification> result = new HashSet<>(unresolved);
    result.addAll(sideEffects);
    return result;
  }
}
