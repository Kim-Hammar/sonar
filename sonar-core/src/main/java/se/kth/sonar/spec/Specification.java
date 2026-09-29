package se.kth.sonar.spec;

import java.util.Locale;
import java.util.Objects;

/**
 * One specification: a forwarding predicate and its status in the faulty network.
 *
 * @param predicate the predicate
 * @param status the status
 */
public record Specification(ForwardingPredicate predicate, Status status) {
  /** Checks the components. */
  public Specification {
    Objects.requireNonNull(predicate, "predicate");
    Objects.requireNonNull(status, "status");
  }

  /** Whether a predicate held in the faulty network as the specification requires. */
  public enum Condition {
    INTACT,
    BROKEN,
    UNKNOWN
  }

  /**
   * A status label from a specification file, with the meaning the benchmark's evaluator gives it.
   *
   * @param label the label as written, such as {@code broken_removed}, without surrounding spaces
   * @param initial whether the specification was intact or broken in the faulty network
   * @param expectPresent whether a repaired network must satisfy the predicate
   */
  public record Status(String label, Condition initial, boolean expectPresent) {
    private static final int BROKEN_PRECEDENCE = 2;
    private static final int OTHER_PRECEDENCE = 1;
    private static final int INTACT_PRECEDENCE = 0;

    /** Checks the components. */
    public Status {
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(initial, "initial");
    }

    /**
     * Interprets {@code label}. A label mentioning {@code broken} is broken and otherwise one
     * mentioning {@code intact} is intact. A broken label mentioning {@code added}, an intact label
     * mentioning {@code added}, and a label mentioning {@code absent} expect the predicate to be
     * absent; every other label expects it to be present.
     */
    public static Status parse(String label) {
      String normalised = label.strip();
      String lower = normalised.toLowerCase(Locale.ROOT);
      boolean broken = lower.contains("broken");
      boolean intact = lower.contains("intact");
      Condition initial = broken ? Condition.BROKEN : intact ? Condition.INTACT : Condition.UNKNOWN;
      boolean added = lower.contains("added");
      boolean removed = lower.contains("removed");
      boolean expectPresent;
      if (broken && added) {
        expectPresent = false;
      } else if (broken && removed) {
        expectPresent = true;
      } else if (added && intact) {
        expectPresent = false;
      } else if (removed && intact) {
        expectPresent = true;
      } else {
        expectPresent = !lower.contains("absent");
      }
      return new Status(normalised, initial, expectPresent);
    }

    /**
     * Returns the precedence of this status when two rows name the same predicate: broken and
     * regression labels rank highest, then any other label except {@code intact}, then {@code
     * intact} and the empty label.
     */
    public int precedence() {
      String lower = label.toLowerCase(Locale.ROOT);
      if (lower.contains("broken") || lower.contains("regression")) {
        return BROKEN_PRECEDENCE;
      }
      return !lower.isEmpty() && !lower.equals("intact") ? OTHER_PRECEDENCE : INTACT_PRECEDENCE;
    }
  }
}
