package se.kth.sonar.text;

import java.util.List;
import java.util.Objects;

/**
 * A change to the lines of one router configuration. Line numbers refer to the configuration the
 * edit is applied to, counted from 1.
 */
public sealed interface LineEdit {
  /** Returns the router whose configuration the edit changes. */
  RouterId router();

  /** Returns the number of lines the edit removes plus the number of lines it adds. */
  int cost();

  /**
   * Deletes the lines of {@code span}.
   *
   * @param span the lines to delete
   */
  record Delete(SourceSpan span) implements LineEdit {
    /** Checks the span. */
    public Delete {
      Objects.requireNonNull(span, "span");
    }

    @Override
    public RouterId router() {
      return span.router();
    }

    @Override
    public int cost() {
      return span.size();
    }
  }

  /**
   * Replaces the lines of {@code span} with {@code lines}.
   *
   * @param span the lines to replace
   * @param lines the replacement, at least one line
   */
  record Replace(SourceSpan span, List<String> lines) implements LineEdit {
    /** Checks the span and the replacement lines. */
    public Replace {
      Objects.requireNonNull(span, "span");
      lines = checkedLines(lines);
    }

    @Override
    public RouterId router() {
      return span.router();
    }

    @Override
    public int cost() {
      return span.size() + lines.size();
    }
  }

  /**
   * Inserts {@code lines} after line {@code line}; line 0 inserts before the first line.
   *
   * @param router the router
   * @param line the line after which to insert
   * @param lines the lines to insert, at least one
   */
  record InsertAfter(RouterId router, int line, List<String> lines) implements LineEdit {
    /** Checks the position and the inserted lines. */
    public InsertAfter {
      Objects.requireNonNull(router, "router");
      if (line < 0) {
        throw new IllegalArgumentException("insertion line must not be negative: " + line);
      }
      lines = checkedLines(lines);
    }

    @Override
    public int cost() {
      return lines.size();
    }
  }

  /**
   * Appends {@code lines} after the last line.
   *
   * @param router the router
   * @param lines the lines to append, at least one
   */
  record Append(RouterId router, List<String> lines) implements LineEdit {
    /** Checks the appended lines. */
    public Append {
      Objects.requireNonNull(router, "router");
      lines = checkedLines(lines);
    }

    @Override
    public int cost() {
      return lines.size();
    }
  }

  private static List<String> checkedLines(List<String> lines) {
    List<String> copy = List.copyOf(lines);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("an edit needs at least one line");
    }
    for (String line : copy) {
      if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("a line must not contain a line break: " + line);
      }
    }
    return copy;
  }
}
