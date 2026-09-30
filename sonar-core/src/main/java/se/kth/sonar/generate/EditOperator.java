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
import se.kth.sonar.text.SourceSpan;

/**
 * A change to a program expressed in terms of its grammar. Every candidate repair is a sequence of
 * these operators; the grammar and the relation schema decide which operators exist, so no repair
 * is written by hand. An operator's statement refers to the text the operator is applied to, which
 * is the result of the operators before it.
 */
public sealed interface EditOperator {
  /** Returns the router the operator changes. */
  RouterId router();

  /** Returns why the operator carries its values. */
  Provenance provenance();

  /** Returns the line edit that performs the operator. */
  LineEdit lower();

  /** Returns the kind of operator and the production it edits or inserts, for grouping. */
  String shape();

  /**
   * Deletes one statement, or the whole block it opens.
   *
   * @param statement the statement
   * @param last the last line to delete, which is the statement's own line unless it opens a block
   */
  record DeleteStatement(Statement statement, int last) implements EditOperator {
    /** Checks the components. */
    public DeleteStatement {
      Objects.requireNonNull(statement, "statement");
      if (last < statement.span().first()) {
        throw new IllegalArgumentException("the block ends before the statement: " + last);
      }
    }

    /** Deletes only the statement's line. */
    public DeleteStatement(Statement statement) {
      this(statement, statement.span().first());
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
      return new LineEdit.Delete(new SourceSpan(router(), statement.span().first(), last));
    }

    @Override
    public String shape() {
      return "delete " + statement.match().map(match -> match.production().id()).orElse("text");
    }
  }

  /**
   * Replaces the values of some slots of a matched statement.
   *
   * @param statement the statement, which must match a production
   * @param values the new values by slot
   * @param provenance where the values come from
   */
  record ReplaceSlots(Statement statement, Map<String, List<String>> values, Provenance provenance)
      implements EditOperator {
    /** Checks that the statement matched and that the values fit their slots. */
    public ReplaceSlots {
      Objects.requireNonNull(statement, "statement");
      Objects.requireNonNull(provenance, "provenance");
      values = frozen(values);
      if (values.isEmpty()) {
        throw new IllegalArgumentException("a replacement changes at least one slot");
      }
      Production.Match match =
          statement
              .match()
              .orElseThrow(
                  () -> new IllegalArgumentException("only a matched statement has slots"));
      match.production().render(merged(match, values));
    }

    /** Replaces the values of one slot. */
    public ReplaceSlots(
        Statement statement, String slot, List<String> values, Provenance provenance) {
      this(statement, Map.of(slot, values), provenance);
    }

    @Override
    public RouterId router() {
      return statement.span().router();
    }

    /** Returns the rendered statement. */
    public String text() {
      Production.Match match = statement.match().orElseThrow();
      return match.production().render(merged(match, values));
    }

    @Override
    public LineEdit lower() {
      return new LineEdit.Replace(statement.span(), List.of(statement.indentation() + text()));
    }

    @Override
    public String shape() {
      return "replace "
          + statement.match().orElseThrow().production().id()
          + " "
          + String.join(",", values.keySet());
    }

    private static Map<String, List<String>> merged(
        Production.Match match, Map<String, List<String>> replacement) {
      Map<String, List<String>> merged = new LinkedHashMap<>(match.values());
      merged.putAll(replacement);
      return merged;
    }
  }

  /**
   * Replaces a statement by a statement of another production, or of the same production with new
   * values, keeping the indentation.
   *
   * @param statement the statement to replace, matched or not
   * @param production the production of the new statement
   * @param values the values of every slot
   * @param provenance where the values come from
   */
  record ReplaceStatement(
      Statement statement,
      Production production,
      Map<String, List<String>> values,
      Provenance provenance)
      implements EditOperator {
    /** Checks that the values render a statement of the production. */
    public ReplaceStatement {
      Objects.requireNonNull(statement, "statement");
      Objects.requireNonNull(production, "production");
      Objects.requireNonNull(provenance, "provenance");
      values = frozen(values);
      production.render(values);
    }

    @Override
    public RouterId router() {
      return statement.span().router();
    }

    @Override
    public LineEdit lower() {
      return new LineEdit.Replace(
          statement.span(), List.of(statement.indentation() + production.render(values)));
    }

    @Override
    public String shape() {
      return "rewrite " + production.id();
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
      values = frozen(values);
      production.render(values);
    }

    /** Returns the rendered statement without indentation. */
    public String text() {
      return production.render(values);
    }

    @Override
    public LineEdit lower() {
      return new LineEdit.InsertAfter(router, after, List.of(indentation + text()));
    }

    @Override
    public String shape() {
      return "insert " + production.id();
    }
  }

  /**
   * Inserts several grammar-checked statements as one atomic program object.
   *
   * <p>This is the block counterpart of {@link InsertStatement}: every line is rendered by a
   * production, while one line edit preserves their nesting and order. It is used when a missing
   * definition and its dependent uses are one relation and cannot become valid one statement at a
   * time.
   *
   * @param router the router
   * @param after the line after which to insert
   * @param statements the statements in configuration order
   * @param provenance why the block is required
   */
  record InsertBlock(
      RouterId router, int after, List<RenderedStatement> statements, Provenance provenance)
      implements EditOperator {
    /** Checks the components and renders every production once. */
    public InsertBlock {
      Objects.requireNonNull(router, "router");
      statements = List.copyOf(statements);
      Objects.requireNonNull(provenance, "provenance");
      if (after < 0 || statements.isEmpty()) {
        throw new IllegalArgumentException("a block has a position and at least one statement");
      }
      statements.forEach(RenderedStatement::text);
    }

    @Override
    public LineEdit lower() {
      return new LineEdit.InsertAfter(
          router, after, statements.stream().map(RenderedStatement::text).toList());
    }

    @Override
    public String shape() {
      return "insert block "
          + String.join(",", statements.stream().map(s -> s.production().id()).toList());
    }
  }

  /**
   * One grammar-rendered line of an inserted block.
   *
   * @param indentation the whitespace before the statement
   * @param production the statement production
   * @param values every slot value
   */
  record RenderedStatement(
      String indentation, Production production, Map<String, List<String>> values) {
    /** Checks that the values render under the production. */
    public RenderedStatement {
      Objects.requireNonNull(indentation, "indentation");
      Objects.requireNonNull(production, "production");
      values = frozen(values);
      production.render(values);
    }

    /** Returns the indented configuration line. */
    public String text() {
      return indentation + production.render(values);
    }
  }

  private static Map<String, List<String>> frozen(Map<String, List<String>> values) {
    SequencedMap<String, List<String>> copy = new LinkedHashMap<>();
    values.forEach((slot, tokens) -> copy.put(slot, List.copyOf(tokens)));
    return Collections.unmodifiableSequencedMap(copy);
  }
}
