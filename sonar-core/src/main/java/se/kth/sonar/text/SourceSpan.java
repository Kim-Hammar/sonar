package se.kth.sonar.text;

import java.util.Objects;

/**
 * A range of whole lines in one router configuration.
 *
 * @param router the router whose configuration contains the lines
 * @param first the first line, counted from 1
 * @param last the last line, inclusive
 */
public record SourceSpan(RouterId router, int first, int last) {
  /** Checks that the range is non-empty and starts at line 1 or later. */
  public SourceSpan {
    Objects.requireNonNull(router, "router");
    if (first < 1 || last < first) {
      throw new IllegalArgumentException("invalid line range " + first + "-" + last);
    }
  }

  /** Returns the span of the single line {@code line}. */
  public static SourceSpan line(RouterId router, int line) {
    return new SourceSpan(router, line, line);
  }

  /** Returns the number of lines in the span. */
  public int size() {
    return last - first + 1;
  }

  @Override
  public String toString() {
    return router + ":" + (first == last ? Integer.toString(first) : first + "-" + last);
  }
}
