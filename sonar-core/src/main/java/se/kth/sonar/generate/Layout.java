package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;

/** Where the statements of one router sit: which lines a block spans and how they are indented. */
final class Layout {
  private final List<Statement> statements;
  private final Map<Integer, Statement> byLine = new HashMap<>();

  Layout(List<Statement> statements) {
    this.statements = statements;
    statements.forEach(statement -> byLine.put(statement.span().first(), statement));
  }

  List<Statement> statements() {
    return statements;
  }

  Optional<Statement> at(int line) {
    return Optional.ofNullable(byLine.get(line));
  }

  /** Returns the statements directly inside the block opened at {@code opener}. */
  List<Statement> inside(int opener) {
    List<Statement> inside = new ArrayList<>();
    for (Statement statement : statements) {
      if (statement.opener() == opener) {
        inside.add(statement);
      }
    }
    return inside;
  }

  /** Returns the last line of the block opened at {@code opener}, or the opener's own line. */
  int end(int opener) {
    int end = opener;
    for (Statement statement : statements) {
      if (statement.span().first() > end && encloses(opener, statement)) {
        end = statement.span().first();
      }
    }
    return end;
  }

  /** Returns whether the block opened at {@code opener} contains {@code statement}. */
  boolean encloses(int opener, Statement statement) {
    int line = statement.opener();
    while (line > 0) {
      if (line == opener) {
        return true;
      }
      Statement above = byLine.get(line);
      line = above == null ? 0 : above.opener();
    }
    return false;
  }

  /** Returns the last line of the router's text as the statements know it. */
  int last() {
    return statements.isEmpty() ? 0 : statements.getLast().span().first();
  }

  /** Returns the indentation of statements inside the block opened at {@code opener}. */
  String indentationInside(int opener) {
    for (Statement statement : statements) {
      if (statement.opener() == opener) {
        return statement.indentation();
      }
    }
    return at(opener).map(Statement::indentation).orElse("") + " ";
  }

  /**
   * Returns the statement that opens a block of {@code context} inside the block at {@code parent}.
   */
  Optional<Statement> opener(Grammar.Context context, int parent) {
    for (Statement statement : statements) {
      Optional<Production> production = statement.match().map(Production.Match::production);
      if (statement.opener() == parent
          && production.flatMap(Production::opens).filter(context::equals).isPresent()) {
        return Optional.of(statement);
      }
    }
    return Optional.empty();
  }
}
