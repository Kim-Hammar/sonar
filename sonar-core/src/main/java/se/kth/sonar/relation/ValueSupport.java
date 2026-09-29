package se.kth.sonar.relation;

/** Where a candidate value comes from, which orders candidates before any evidence is weighed. */
public enum ValueSupport {
  /** The value occurs in the program or in the verifier's observations. */
  OBSERVED,
  /** The value is invented to complete a structure, such as a fresh name. */
  INVENTED
}
