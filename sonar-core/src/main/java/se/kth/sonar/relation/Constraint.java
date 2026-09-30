package se.kth.sonar.relation;

import java.util.Objects;

/** A condition on the objects of one relation instance, or across all instances. */
public sealed interface Constraint {
  /**
   * The path has a value: an optional role is filled, a flag is set, or an attribute is present.
   *
   * @param path the path
   */
  record Exists(AttributePath path) implements Constraint {
    /** Checks the path. */
    public Exists {
      Objects.requireNonNull(path, "path");
    }
  }

  /**
   * The path has no value: the attribute is absent, the flag is not set, or the role is unfilled.
   *
   * @param path the path
   */
  record Absent(AttributePath path) implements Constraint {
    /** Checks the path. */
    public Absent {
      Objects.requireNonNull(path, "path");
    }
  }

  /**
   * Both paths have values and the values are equal.
   *
   * @param left one path
   * @param right the other path
   */
  record Equal(AttributePath left, AttributePath right) implements Constraint {
    /** Checks the paths. */
    public Equal {
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }
  }

  /**
   * Either both paths lack a value or both have equal values.
   *
   * @param left one path
   * @param right the other path
   */
  record Agree(AttributePath left, AttributePath right) implements Constraint {
    /** Checks the paths. */
    public Agree {
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }
  }

  /**
   * No two instances of the relation have the same value at the path.
   *
   * @param path the path
   */
  record Distinct(AttributePath path) implements Constraint {
    /** Checks the path. */
    public Distinct {
      Objects.requireNonNull(path, "path");
    }
  }

  /**
   * The reference at the path names an object that exists.
   *
   * @param path a path that ends at a reference attribute
   */
  record Resolves(AttributePath path) implements Constraint {
    /** Checks the path. */
    public Resolves {
      Objects.requireNonNull(path, "path");
    }
  }
}
