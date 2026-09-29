package se.kth.sonar.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.RunScope;

class TraceSinkTest {
  private static final TraceEvent EVENT = new TraceEvent.Failed("boom");

  @Test
  void emittingWithoutASinkDoesNothing() {
    TraceSink.emit(EVENT);
    assertThat(TraceSink.CURRENT.isBound()).isFalse();
  }

  @Test
  void recordsCarryTheScenarioAndItsUsage() {
    List<TraceRecord> records = new ArrayList<>();
    Budget budget = Budget.unlimited();
    budget.charge(Budget.Resource.INPUT_TOKENS, 120);
    RunScope scope = new RunScope("scenario-001", budget, 1L, new Cancellation());
    ScopedValue.where(RunScope.CURRENT, scope)
        .where(TraceSink.CURRENT, records::add)
        .run(() -> TraceSink.emit(EVENT));
    assertThat(records)
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.scenario()).isEqualTo("scenario-001");
              assertThat(record.usage()).containsEntry(Budget.Resource.INPUT_TOKENS, 120L);
              assertThat(record.event()).isSameAs(EVENT);
              assertThat(record.at()).isNotNull();
            });
  }

  @Test
  void recordsOutsideARunHaveNoScenario() {
    List<TraceRecord> records = new ArrayList<>();
    ScopedValue.where(TraceSink.CURRENT, records::add).run(() -> TraceSink.emit(EVENT));
    assertThat(records)
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.scenario()).isEmpty();
              assertThat(record.usage()).isEmpty();
            });
  }
}
