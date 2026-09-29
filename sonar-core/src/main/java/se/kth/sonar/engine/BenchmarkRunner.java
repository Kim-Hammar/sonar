package se.kth.sonar.engine;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceSink;

/**
 * Repairs many scenarios, several at a time.
 *
 * <p>Scenarios run on virtual threads, up to the scenario parallelism of the execution context.
 * Each scenario is loaded only when it starts, gets a fresh budget, and binds its own run scope and
 * the trace sink on its orchestrating thread. A scenario that fails does not stop the others, and
 * after cancellation the scenarios that have not started are skipped. Outcomes come back in
 * scenario order.
 */
public final class BenchmarkRunner {
  private final Repairer repairer;
  private final ExecutionContext context;

  /** Creates a runner that repairs with {@code repairer} on {@code context}. */
  public BenchmarkRunner(Repairer repairer, ExecutionContext context) {
    this.repairer = Objects.requireNonNull(repairer, "repairer");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** What happened to one scenario. */
  public sealed interface Outcome {
    /** Returns the scenario's name. */
    String scenario();

    /**
     * The repair ran to its end.
     *
     * @param result the repair's result
     */
    record Completed(RepairResult result) implements Outcome {
      /** Checks the result. */
      public Completed {
        Objects.requireNonNull(result, "result");
      }

      @Override
      public String scenario() {
        return result.scenario();
      }
    }

    /**
     * The scenario could not be loaded or repaired.
     *
     * @param scenario the scenario's name
     * @param reason what went wrong
     */
    record Failed(String scenario, String reason) implements Outcome {}

    /**
     * The run was cancelled before or while the scenario ran.
     *
     * @param scenario the scenario's name
     */
    record Cancelled(String scenario) implements Outcome {}
  }

  /**
   * Repairs {@code scenarios}. Each scenario emits {@link TraceEvent.Loaded} once loaded, then
   * {@link TraceEvent.Finished} or {@link TraceEvent.Failed}, besides the events of the repair.
   *
   * @param scenarios the scenarios' names
   * @param loader loads a scenario by name
   * @param budgets creates the budget of each scenario
   * @param seed the seed of every scenario's random choices
   * @param cancellation the token that asks the run to stop
   * @param sink receives the trace of every scenario
   */
  public List<Outcome> run(
      List<String> scenarios,
      Function<String, RepairProblem> loader,
      Supplier<Budget> budgets,
      long seed,
      Cancellation cancellation,
      TraceSink sink) {
    return context.fanOut(
        scenarios,
        context.config().scenarioParallelism(),
        scenario -> {
          if (cancellation.isCancelled()) {
            return new Outcome.Cancelled(scenario);
          }
          RunScope scope = new RunScope(scenario, budgets.get(), seed, cancellation);
          return ScopedValue.where(RunScope.CURRENT, scope)
              .where(TraceSink.CURRENT, sink)
              .call(() -> repairOne(scenario, loader, cancellation));
        });
  }

  private Outcome repairOne(
      String scenario, Function<String, RepairProblem> loader, Cancellation cancellation) {
    try {
      RepairProblem problem = loader.apply(scenario);
      TraceSink.emit(new TraceEvent.Loaded(problem));
      RepairResult result = repairer.repair(problem);
      TraceSink.emit(new TraceEvent.Finished(result));
      return new Outcome.Completed(result);
    } catch (RuntimeException failure) {
      if (cancellation.isCancelled()) {
        TraceSink.emit(new TraceEvent.Failed("the run was cancelled"));
        return new Outcome.Cancelled(scenario);
      }
      String reason =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      TraceSink.emit(new TraceEvent.Failed(reason));
      return new Outcome.Failed(scenario, reason);
    }
  }
}
