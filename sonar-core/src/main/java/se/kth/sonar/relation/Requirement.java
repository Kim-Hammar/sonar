package se.kth.sonar.relation;

import java.util.Objects;
import java.util.Optional;

/**
 * A constraint that the instances of a relation should meet.
 *
 * @param strength whether a repair must keep the constraint or merely prefers to
 * @param constraint the constraint
 * @param guard a path that must have a value for the requirement to apply, if any
 */
public record Requirement(Strength strength, Constraint constraint, Optional<AttributePath> guard) {
  /** How binding a requirement is. */
  public enum Strength {
    /** Every admissible repair keeps the constraint. */
    HARD,
    /** Repairs that keep the constraint are preferred; the closure offers them as alternatives. */
    SOFT
  }

  /** Checks the components. */
  public Requirement {
    Objects.requireNonNull(strength, "strength");
    Objects.requireNonNull(constraint, "constraint");
    Objects.requireNonNull(guard, "guard");
  }
}
