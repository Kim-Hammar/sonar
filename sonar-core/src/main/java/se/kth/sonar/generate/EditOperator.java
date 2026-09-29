package se.kth.sonar.generate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;

/**
 * A change to a program expressed in terms of its grammar. Every candidate repair is a sequence of
 * these three operators; the grammar and the relation schema decide which operators exist, so no
 * repair is written by hand.
 */
public sealed interface EditOperator {
  /** Returns the router the operator changes. */
  RouterId router();

  /** Returns why the operator carries its values. */
  Provenance provenance();

  /** Returns the line edit that performs the operator. */
  LineEdit lower();

  /**
   * Deletes one statement.
   *
   * @param statement the statement
   */
  record DeleteStatement(Statement statement) implements EditOperator {
    /** Checks the statement. */
    public DeleteStatement {
      Objects.requireNonNull(statement, "statement");
    }

    @Override
    public RouterId router() {
      return statement.span().router();
    }

    @Override
    public Provenance provenance() {
      return new Provenance.Existing();
    }

    @Override
    public LineEdit lower() {
      return new LineEdit.Delete(statement.span());
    }
  }

  /**
   * Replaces the values of one slot of a matched statement.
   *
   * @param statement the statement, which must match a production
   * @param slot the slot's name
   * @param values the new values
   * @param provenance where the values come from
   */
  record ReplaceSlot(Statement statement, String slot, List<String> values, Provenance provenance)
      implements EditOperator {
    /** Checks that the statement matched and that the values fit the slot. */
    public ReplaceSlot {
      Objects.requireNonNull(statement, "statement");
      Objects.requireNonNull(provenance, "provenance");
      values = List.copyOf(values);
      statement
          .match()
          .orElseThrow(() -> new IllegalArgumentException("only a matched statement has slots"))
          .withValues(slot, values);
    }

    @Override
    public RouterId router() {
      return statement.span().router();
    }

    @Override
    public LineEdit lower() {
      String text = statement.match().orElseThrow().withValues(slot, values).render();
      return new LineEdit.Replace(statement.span(), List.of(statement.indentation() + text));
    }
  }

  /**
   * Inserts a new statement.
   *
   * @param router the router
   * @param after the line after which to insert; 0 inserts before the first line
   * @param indentation the whitespace to write before the statement
   * @param production the production of the new statement
   * @param values the values of every slot
   * @param provenance where the values come from
   */
  record InsertStatement(
      RouterId router,
      int after,
      String indentation,
      Production production,
      Map<String, List<String>> values,
      Provenance provenance)
      implements EditOperator {
    /** Checks that the values render a statement of the production. */
    public InsertStatement {
      Objects.requireNonNull(router, "router");
      Objects.requireNonNull(indentation, "indentation");
      Objects.requireNonNull(production, "production");
      Objects.requireNonNull(provenance, "provenance");
      SequencedMap<String, List<String>> copy = new LinkedHashMap<>();
      values.forEach((slot, tokens) -> copy.put(slot, List.copyOf(tokens)));
      values = Collections.unmodifiableSequencedMap(copy);
      production.render(values);
    }

    @Override
    public LineEdit lower() {
      return new LineEdit.InsertAfter(
          router, after, List.of(indentation + production.render(values)));
    }
  }
}
