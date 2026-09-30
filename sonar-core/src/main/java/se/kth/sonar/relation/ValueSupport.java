package se.kth.sonar.relation;

/**
 * Where a candidate value comes from, strongest first, which orders candidates before any evidence
 * is weighed.
 */
public enum ValueSupport {
  /** The value comes from the verifier's observations or from the relation instance completed. */
  EVIDENCED,
  /** The value occurs in the program. */
  OBSERVED,
  /** The value is invented to complete a structure, such as a fresh name. */
  INVENTED;

  /** Returns the weaker of two supports. */
  public ValueSupport weaker(ValueSupport other) {
    return compareTo(other) >= 0 ? this : other;
  }
}
