package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.state.BgpSessionCut;
import se.kth.sonar.text.SourceSpan;

/** Deletes both address-specific definitions of an observed forbidden BGP session atomically. */
public final class BgpSessionCutGenerator {
  /** Returns the unique transaction that suppresses {@code cut}. */
  public List<Hypothesis> generate(ProgramModel program, BgpSessionCut cut) {
    List<EditOperator> operators = new ArrayList<>();
    Set<SourceSpan> seen = new LinkedHashSet<>();
    for (ProgramObject.Ref ref : cut.session().roles().values()) {
      if (program.object(ref).isEmpty() || ref.key().size() != 1) {
        return List.of();
      }
      String peer = ref.key().getFirst();
      for (Statement statement : program.statements(ref.router())) {
        boolean use =
            statement
                .match()
                .filter(match -> match.production().id().startsWith("neighbor-"))
                .map(match -> match.values("peer").contains(peer))
                .orElse(false);
        if (use && seen.add(statement.span())) {
          operators.add(new EditOperator.DeleteStatement(statement));
        }
      }
    }
    if (operators.isEmpty()) {
      return List.of();
    }
    try {
      return List.of(
          new Hypothesis(
              Transaction.of(
                  program.snapshot(), operators.stream().map(EditOperator::lower).toList()),
              operators));
    } catch (IllegalArgumentException invalid) {
      return List.of();
    }
  }
}
