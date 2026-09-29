package se.kth.sonar.trace;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import se.kth.sonar.execution.Budget;

/**
 * One trace event with the context in which it happened.
 *
 * @param scenario the scenario whose run emitted the event, or empty outside a run
 * @param at when the event was emitted
 * @param usage what the scenario had consumed when the event was emitted
 * @param event the event
 */
public record TraceRecord(
    String scenario, Instant at, Map<Budget.Resource, Long> usage, TraceEvent event) {

  /** Copies the usage and checks the components. */
  public TraceRecord {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(at, "at");
    usage = Map.copyOf(usage);
    Objects.requireNonNull(event, "event");
  }
}
