package se.kth.sonar.common;

/**
 * Thrown by a member of a {@link Placeholder} type that has a contract but no implementation yet.
 */
public final class NotImplemented extends UnsupportedOperationException {
  /** Creates the exception for {@code member}, written as {@code Type.method}. */
  public NotImplemented(String member) {
    super(member + " is not implemented yet");
  }
}
