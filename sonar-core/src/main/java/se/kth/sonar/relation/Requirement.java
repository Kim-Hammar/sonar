package se.kth.sonar.relation;

import java.util.Locale;
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
    SOFT,
    /**
     * A design habit: a violation is neither a fault signal nor a repair target, but the closure
     * completes an edit that breaks it, to keep the configuration consistent.
     */
    CONVENTION
  }

  /** Checks the components. */
  public Requirement {
    Objects.requireNonNull(strength, "strength");
    Objects.requireNonNull(constraint, "constraint");
    Objects.requireNonNull(guard, "guard");
  }

  /** Returns whether a violation of the requirement signals a fault to repair. */
  public boolean signals() {
    return strength != Strength.CONVENTION;
  }

  /** Returns the requirement as written in the relation language. */
  public String text() {
    String body =
        switch (constraint) {
          case Constraint.Exists exists -> "exists " + exists.path();
          case Constraint.Absent absent -> "absent " + absent.path();
          case Constraint.Equal equal -> "equal " + equal.left() + " " + equal.right();
          case Constraint.Agree agree -> "agree " + agree.left() + " " + agree.right();
          case Constraint.Resolves resolves -> "resolves " + resolves.path();
          case Constraint.Distinct distinct -> "distinct " + distinct.path();
        };
    String text = strength.name().toLowerCase(Locale.ROOT) + " " + body;
    return guard.map(path -> text + " when " + path).orElse(text);
  }
}
