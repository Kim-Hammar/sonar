package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.LineDiff;
import se.kth.sonar.text.LineEdit;
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
  }

  /**
   * Applies {@code operators} one after another to {@code base}, each to the text the earlier ones
   * produced, and expresses the result as one transaction against {@code base}.
   *
   * @throws IllegalArgumentException if there is no operator or the operators change nothing
   */
  public static Hypothesis of(Snapshot base, List<? extends EditOperator> operators) {
    if (operators.isEmpty()) {
      throw new IllegalArgumentException("a hypothesis needs at least one operator");
    }
    Map<RouterId, ConfigText> working = new LinkedHashMap<>();
    for (EditOperator operator : operators) {
      RouterId router = operator.router();
      ConfigText current = working.getOrDefault(router, base.text(router));
      working.put(router, current.apply(List.of(operator.lower())));
    }
    List<LineEdit> edits = new ArrayList<>();
    working.forEach(
        (router, text) -> edits.addAll(LineDiff.between(router, base.text(router), text)));
    if (edits.isEmpty()) {
      throw new IllegalArgumentException("the operators change nothing");
    }
    return new Hypothesis(Transaction.of(base, edits), List.copyOf(operators));
  }

  /** Returns the transaction's identifier. */
  public ContentHash id() {
    return transaction.id();
  }

  /** Returns the routers the hypothesis changes. */
  public NavigableSet<RouterId> routers() {
    return new TreeSet<>(transaction.routers());
  }

  /** Returns the weakest support among the operators' values. */
  public ValueSupport support() {
    ValueSupport support = ValueSupport.EVIDENCED;
    for (EditOperator operator : operators) {
      support = support.weaker(operator.provenance().support());
    }
    return support;
  }

  /** Returns the number of lines removed plus the number of lines added. */
  public int cost() {
    return transaction.cost();
  }

  /** Returns the relation requirements the operators were forced by, without repeats. */
  public List<Provenance.Restored> restores() {
    List<Provenance.Restored> restored = new ArrayList<>();
    for (EditOperator operator : operators) {
      if (operator.provenance() instanceof Provenance.Forced forced
          && !restored.contains(forced.restored())) {
        restored.add(forced.restored());
      }
    }
    return restored;
  }

  /**
   * Returns the class of transactions the hypothesis belongs to: the requirements it restores, or,
   * when it restores none, the shapes of its operators. Hypotheses of one class are exchangeable
   * observations of whether that kind of edit repairs the network.
   */
  public ContentHash signature() {
    ContentHash.Builder digest = ContentHash.builder();
    List<Provenance.Restored> restored = restores();
    if (restored.isEmpty()) {
      digest.add("shapes");
      operators.stream().map(EditOperator::shape).sorted().forEach(digest::add);
    } else {
      digest.add("restores");
      restored.stream().map(Provenance.Restored::toString).sorted().forEach(digest::add);
    }
    return digest.build();
  }

  /** Returns whether the operators restore {@code requirement} of {@code relation}. */
  public boolean restores(String relation, Requirement requirement) {
    return restores().contains(new Provenance.Restored(relation, requirement));
  }
}
