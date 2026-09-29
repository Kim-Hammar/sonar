package se.kth.sonar.grammar;

import java.util.Objects;

/** One position of a production: a fixed keyword or a typed slot. */
public sealed interface Symbol {
  /** Returns whether {@code token} can stand in this position. */
  boolean accepts(String token);

  /** Returns whether some token can stand in both positions. */
  boolean overlaps(Symbol other);

  /**
   * A fixed token.
   *
   * @param text the token
   */
  record Keyword(String text) implements Symbol {
    /** Checks that the keyword is a single non-empty token. */
    public Keyword {
      if (text.isEmpty() || text.chars().anyMatch(Character::isWhitespace)) {
        throw new IllegalArgumentException("invalid keyword: '" + text + "'");
      }
    }

    @Override
    public boolean accepts(String token) {
      return text.equals(token);
    }

    @Override
    public boolean overlaps(Symbol other) {
      return other.accepts(text);
    }

    @Override
    public String toString() {
      return text;
    }
  }

  /**
   * A typed value.
   *
   * @param name the slot's name, unique within its production
   * @param sort the values the slot accepts
   * @param repeated whether the slot takes one or more values instead of exactly one
   */
  record Slot(String name, ValueSort sort, boolean repeated) implements Symbol {
    /** Checks the components. */
    public Slot {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(sort, "sort");
    }

    @Override
    public boolean accepts(String token) {
      return sort.accepts(token);
    }

    @Override
    public boolean overlaps(Symbol other) {
      return switch (other) {
        case Keyword keyword -> sort.accepts(keyword.text());
        case Slot slot -> sort.overlaps(slot.sort());
      };
    }

    @Override
    public String toString() {
      return "<" + name + ":" + sort.name() + ">" + (repeated ? "+" : "");
    }
  }
}
