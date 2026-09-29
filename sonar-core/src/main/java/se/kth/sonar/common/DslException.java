package se.kth.sonar.common;

import java.util.Objects;

/** A syntax or validation error in a dialect source, located by origin and line. */
public final class DslException extends RuntimeException {
  private final String origin;
  private final int line;

  /** Creates the exception; its message reads {@code origin:line: reason}. */
  public DslException(String origin, int line, String reason) {
    super(Objects.requireNonNull(origin, "origin") + ":" + line + ": " + reason);
    this.origin = origin;
    this.line = line;
  }

  /** Returns where the source came from. */
  public String origin() {
    return origin;
  }

  /** Returns the 1-based line of the error. */
  public int line() {
    return line;
  }
}
