package se.kth.sonar.relation;

import java.util.Objects;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.GrammarDsl;

/**
 * A configuration dialect: its grammar and the relation schema written against it.
 *
 * @param grammar the syntax
 * @param schema the semantics
 */
public record Dialect(Grammar grammar, RelationSchema schema) {
  private static final String LOCATION = "/se/kth/sonar/dialect/";

  /** Checks that the schema is written against the grammar. */
  public Dialect {
    Objects.requireNonNull(grammar, "grammar");
    Objects.requireNonNull(schema, "schema");
    if (schema.grammar() != grammar) {
      throw new IllegalArgumentException("the schema is written against another grammar");
    }
  }

  /**
   * Loads the dialect shipped as {@code name.grammar} and {@code name.relations} on the class path,
   * such as {@code cisco-ios}.
   */
  public static Dialect load(String name) {
    Grammar grammar = GrammarDsl.load(LOCATION + name + ".grammar");
    return new Dialect(grammar, RelationDsl.load(LOCATION + name + ".relations", grammar));
  }
}
