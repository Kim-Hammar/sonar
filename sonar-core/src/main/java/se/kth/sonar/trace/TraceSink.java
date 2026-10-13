package se.kth.sonar.trace;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import se.kth.sonar.execution.RunScope;

/**
 * Receives the trace of a run. Each scenario emits its events from its own orchestrating thread, so
 * the events of one scenario arrive in an order that does not depend on parallelism; events of
 * different scenarios may interleave, and implementations must accept them from several threads.
 */
public interface TraceSink {
  /** The trace sink bound to the orchestrating thread of the current scenario. */
  ScopedValue<TraceSink> CURRENT = ScopedValue.newInstance();

  /** Receives one record. */
  void accept(TraceRecord record);

  /**
   * Sends {@code event} to the sink bound to the current thread, if any, stamped with the scenario
   * and budget usage of the current {@link RunScope} and the current time.
   */
  static void emit(TraceEvent event) {
    if (!CURRENT.isBound()) {
      return;
    }
    Optional<RunScope> scope = RunScope.current();
    CURRENT
        .get()
        .accept(
            new TraceRecord(
                scope.map(RunScope::scenario).orElse(""),
                Instant.now(),
                scope.map(current -> current.budget().usage()).orElse(Map.of()),
                event));
  }
}
