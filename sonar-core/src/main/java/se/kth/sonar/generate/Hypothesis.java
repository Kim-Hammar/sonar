package se.kth.sonar.generate;

import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.text.RouterId;

/**
 * A candidate repair: a transaction and the operators it was lowered from.
 *
 * @param transaction the line edits
 * @param operators the operators, in the order they were generated
 */
public record Hypothesis(Transaction transaction, List<EditOperator> operators) {
  /** Checks the components. */
  public Hypothesis {
    Objects.requireNonNull(transaction, "transaction");
    operators = List.copyOf(operators);
    if (operators.isEmpty()) {
      throw new IllegalArgumentException("a hypothesis has at least one operator");
    }
  }

  /** Lowers {@code operators} into a transaction against {@code base}. */
  public static Hypothesis of(Snapshot base, List<? extends EditOperator> operators) {
    return new Hypothesis(
        Transaction.of(base, operators.stream().map(EditOperator::lower).toList()),
        List.copyOf(operators));
  }

  /** Returns the transaction's identifier. */
  public ContentHash id() {
    return transaction.id();
  }

  /** Returns the routers the hypothesis changes. */
  public NavigableSet<RouterId> routers() {
    return transaction.routers();
  }

  /** Returns {@link ValueSupport#INVENTED} if any operator invents a value. */
  public ValueSupport support() {
    return operators.stream().anyMatch(op -> op.provenance().support() == ValueSupport.INVENTED)
        ? ValueSupport.INVENTED
        : ValueSupport.OBSERVED;
  }

  /** Returns the number of lines removed plus the number of lines added. */
  public int cost() {
    return transaction.cost();
  }
}
