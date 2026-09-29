package se.kth.sonar.grammar;

import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.text.SourceSpan;

/**
 * One line of a configuration, placed in the context it belongs to.
 *
 * @param span the line
 * @param context the context the line belongs to
 * @param opener the line of the statement that opened the context, or 0 in the root context
 * @param indentation the whitespace before the first token, as written
 * @param text the line without its indentation
 * @param match the production the line matches with its slot values, or nothing if none matches
 */
public record Statement(
    SourceSpan span,
    Grammar.Context context,
    int opener,
    String indentation,
    String text,
    Optional<Production.Match> match) {

  /** Checks the components. */
  public Statement {
    Objects.requireNonNull(span, "span");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(indentation, "indentation");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(match, "match");
    if (opener < 0 || opener >= span.first()) {
      throw new IllegalArgumentException("the opener must precede the statement: " + opener);
    }
  }

  /** Returns whether a production matches the line. */
  public boolean matched() {
    return match.isPresent();
  }
}
