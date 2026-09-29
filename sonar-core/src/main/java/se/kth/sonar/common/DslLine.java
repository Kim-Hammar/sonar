package se.kth.sonar.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One significant line of a dialect source, split into whitespace-separated tokens.
 *
 * @param origin where the source came from, for error messages
 * @param number the 1-based line number
 * @param tokens the tokens, never empty
 */
public record DslLine(String origin, int number, List<String> tokens) {
  private static final Pattern LINE_BREAK = Pattern.compile("\r?\n");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Checks that the line has at least one token. */
  public DslLine {
    Objects.requireNonNull(origin, "origin");
    tokens = List.copyOf(tokens);
    if (tokens.isEmpty()) {
      throw new IllegalArgumentException("a significant line has at least one token");
    }
  }

  /**
   * Splits {@code source} into significant lines. Blank lines and lines whose first token starts
   * with {@code #} are skipped.
   */
  public static List<DslLine> read(String source, String origin) {
    List<DslLine> lines = new ArrayList<>();
    String[] raw = LINE_BREAK.split(source, -1);
    for (int index = 0; index < raw.length; index++) {
      String trimmed = raw[index].strip();
      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        lines.add(new DslLine(origin, index + 1, List.of(WHITESPACE.split(trimmed))));
      }
    }
    return List.copyOf(lines);
  }

  /** Returns the first token, which names the declaration. */
  public String keyword() {
    return tokens.getFirst();
  }

  /** Returns an error located at this line. */
  public DslException error(String reason) {
    return new DslException(origin, number, reason);
  }

  /** Returns a cursor over the tokens after the keyword. */
  public Cursor cursor() {
    return new Cursor(this);
  }

  /** Reads the tokens of one line from left to right and reports errors at that line. */
  public static final class Cursor {
    private final DslLine line;
    private int position = 1;

    private Cursor(DslLine line) {
      this.line = line;
    }

    /** Returns whether tokens remain. */
    public boolean hasNext() {
      return position < line.tokens().size();
    }

    /** Returns the next token without consuming it. */
    public Optional<String> peek() {
      return hasNext() ? Optional.of(line.tokens().get(position)) : Optional.empty();
    }

    /** Consumes and returns the next token; {@code expected} describes it in the error. */
    public String next(String expected) {
      if (!hasNext()) {
        throw line.error(
            "expected " + expected + " after '" + String.join(" ", line.tokens()) + "'");
      }
      return line.tokens().get(position++);
    }

    /** Consumes the next token if it equals {@code token} and reports whether it did. */
    public boolean accept(String token) {
      if (peek().filter(token::equals).isPresent()) {
        position++;
        return true;
      }
      return false;
    }

    /** Consumes {@code token} or fails. */
    public void expect(String token) {
      String actual = next("'" + token + "'");
      if (!actual.equals(token)) {
        throw line.error("expected '" + token + "' but found '" + actual + "'");
      }
    }

    /** Consumes and returns every remaining token. */
    public List<String> rest() {
      List<String> rest = line.tokens().subList(position, line.tokens().size());
      position = line.tokens().size();
      return List.copyOf(rest);
    }

    /** Fails if tokens remain. */
    public void end() {
      if (hasNext()) {
        throw line.error("unexpected '" + line.tokens().get(position) + "'");
      }
    }

    /** Returns an error located at the line being read. */
    public DslException error(String reason) {
      return line.error(reason);
    }
  }
}
