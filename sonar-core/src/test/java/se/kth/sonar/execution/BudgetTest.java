package se.kth.sonar.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BudgetTest {
  @Test
  void tryConsumeStaysWithinTheLimit() {
    Budget budget = Budget.builder().limit(Budget.Resource.VERIFICATIONS, 3).build();
    assertThat(budget.tryConsume(Budget.Resource.VERIFICATIONS, 2)).isTrue();
    assertThat(budget.tryConsume(Budget.Resource.VERIFICATIONS, 2)).isFalse();
    assertThat(budget.used(Budget.Resource.VERIFICATIONS)).isEqualTo(2);
    assertThat(budget.remaining(Budget.Resource.VERIFICATIONS)).hasValue(1);
    assertThat(budget.tryConsume(Budget.Resource.VERIFICATIONS, 1)).isTrue();
    assertThat(budget.exhausted(Budget.Resource.VERIFICATIONS)).isTrue();
  }

  @Test
  void unlimitedResourcesHaveNoRemainder() {
    Budget budget = Budget.unlimited();
    assertThat(budget.tryConsume(Budget.Resource.DECISIONS, 1_000_000)).isTrue();
    assertThat(budget.remaining(Budget.Resource.DECISIONS)).isEmpty();
    assertThat(budget.exhausted(Budget.Resource.DECISIONS)).isFalse();
    assertThat(budget.expired()).isFalse();
  }

  @Test
  void chargeRecordsUsageBeyondTheLimit() {
    Budget budget = Budget.builder().limit(Budget.Resource.INPUT_TOKENS, 10).build();
    budget.charge(Budget.Resource.INPUT_TOKENS, 25);
    assertThat(budget.used(Budget.Resource.INPUT_TOKENS)).isEqualTo(25);
    assertThat(budget.remaining(Budget.Resource.INPUT_TOKENS)).hasValue(0);
    assertThat(budget.usage()).containsEntry(Budget.Resource.INPUT_TOKENS, 25L);
  }

  @Test
  void concurrentConsumersNeverExceedTheLimit() {
    Budget budget = Budget.builder().limit(Budget.Resource.DECISIONS, 1000).build();
    AtomicInteger granted = new AtomicInteger();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int task = 0; task < 5000; task++) {
        executor.execute(
            () -> {
              if (budget.tryConsume(Budget.Resource.DECISIONS, 1)) {
                granted.incrementAndGet();
              }
            });
      }
    }
    assertThat(granted).hasValue(1000);
    assertThat(budget.used(Budget.Resource.DECISIONS)).isEqualTo(1000);
  }

  @Test
  void theTimeLimitFollowsTheClock() {
    ManualClock clock = new ManualClock();
    Budget budget = Budget.builder().timeLimit(Duration.ofSeconds(10)).clock(clock).build();
    clock.advance(Duration.ofSeconds(9));
    assertThat(budget.expired()).isFalse();
    clock.advance(Duration.ofSeconds(1));
    assertThat(budget.expired()).isTrue();
    assertThat(budget.elapsed()).isEqualTo(Duration.ofSeconds(10));
  }

  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
