package se.kth.sonar.report;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.spec.Verdict;

/**
 * The outcome of repairing one scenario.
 *
 * @param scenario the scenario's name
 * @param repaired the final configurations
 * @param verdict the verdict of the final configurations
 * @param adopted the transactions adopted, in order
 * @param stop why the run stopped
 * @param usage what the run consumed
 */
public record RepairResult(
    String scenario,
    Snapshot repaired,
    Verdict verdict,
    List<Transaction> adopted,
    Stop stop,
    Map<Budget.Resource, Long> usage) {

  /** Copies the collections. */
  public RepairResult {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(repaired, "repaired");
    Objects.requireNonNull(verdict, "verdict");
    adopted = List.copyOf(adopted);
    Objects.requireNonNull(stop, "stop");
    usage = Map.copyOf(usage);
  }

  /** Returns whether the run repaired every violated specification. */
  public boolean solved() {
    return stop instanceof Stop.Solved;
  }

  /** Why a run stopped. */
  public sealed interface Stop {
    /** Every specification holds. */
    record Solved() implements Stop {}

    /**
     * A resource ran out.
     *
     * @param resource the resource
     */
    record BudgetExhausted(Budget.Resource resource) implements Stop {}

    /** The time limit passed. */
    record TimeExpired() implements Stop {}

    /** The run was asked to stop. */
    record Cancelled() implements Stop {}

    /** No candidate is left to try. */
    record SearchExhausted() implements Stop {}

    /** The policy judged that further search would cost more than the repair is worth. */
    record Abandoned() implements Stop {}

    /**
     * The run failed.
     *
     * @param reason what went wrong
     */
    record Failed(String reason) implements Stop {}
  }
}
