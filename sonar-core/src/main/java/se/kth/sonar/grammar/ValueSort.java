package se.kth.sonar.grammar;

import java.util.Objects;

/**
 * A named set of tokens that a slot accepts, such as AS numbers or interface names.
 *
 * @param name the sort's name in the grammar
 * @param lexeme the tokens the sort accepts
 */
public record ValueSort(String name, Lexeme lexeme) {
  /** Checks the components. */
  public ValueSort {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(lexeme, "lexeme");
  }

  /** Returns whether {@code token} is a value of this sort. */
  public boolean accepts(String token) {
    return lexeme.accepts(token);
  }

  /** Returns whether some token is a value of both sorts. */
  public boolean overlaps(ValueSort other) {
    return Lexeme.overlap(lexeme, other.lexeme);
  }
}
