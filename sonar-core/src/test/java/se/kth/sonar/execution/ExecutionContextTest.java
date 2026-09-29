package se.kth.sonar.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchRuntimeException;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExecutionContextTest {
  private static final ScopedValue<String> TAG = ScopedValue.newInstance();

  private static ParallelismConfig parallel() {
    return new ParallelismConfig(2, 4, 4, 1, 1);
  }

  private static void pause() {
    try {
      Thread.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void fanOutKeepsInputOrderAndBoundsConcurrency() {
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    List<Integer> inputs = IntStream.range(0, 40).boxed().toList();
    try (ExecutionContext context = ExecutionContext.open(parallel(), List.of())) {
      List<Integer> results =
          context.fanOut(
              inputs,
              4,
              input -> {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                pause();
                inFlight.decrementAndGet();
                return input * input;
              });
      assertThat(results).isEqualTo(inputs.stream().map(input -> input * input).toList());
    }
    assertThat(peak.get()).isBetween(1, 4);
  }

  @Test
  void fanOutRethrowsTheEarliestFailureAfterEveryCallFinishes() {
    AtomicInteger calls = new AtomicInteger();
    try (ExecutionContext context = ExecutionContext.open(parallel(), List.of())) {
      RuntimeException thrown =
          catchRuntimeException(
              () ->
                  context.fanOut(
                      List.of(0, 1, 2, 3, 4, 5),
                      3,
                      input -> {
                        calls.incrementAndGet();
                        if (input == 4) {
                          throw new IllegalStateException("four");
                        }
                        if (input == 2) {
                          throw new IllegalArgumentException("two");
                        }
                        return input;
                      }));
      assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage("two");
      assertThat(thrown.getSuppressed()).hasSize(1);
      assertThat(thrown.getSuppressed()[0]).hasMessage("four");
    }
    assertThat(calls).hasValue(6);
  }

  @Test
  void theSequentialConfigurationRunsOnTheCallingThread() {
    AtomicInteger calls = new AtomicInteger();
    try (ExecutionContext context =
        ExecutionContext.open(ParallelismConfig.sequential(), List.of())) {
      Thread caller = Thread.currentThread();
      assertThat(context.fanOut(List.of(1, 2, 3), 1, input -> Thread.currentThread()))
          .containsOnly(caller);
      assertThat(context.compute(List.of(1, 2, 3), input -> Thread.currentThread()))
          .containsOnly(caller);
      assertThatThrownBy(
              () ->
                  context.fanOut(
                      List.of(1, 2, 3),
                      1,
                      input -> {
                        calls.incrementAndGet();
                        if (input == 2) {
                          throw new IllegalStateException("stop");
                        }
                        return input;
                      }))
          .hasMessage("stop");
    }
    assertThat(calls).hasValue(2);
  }

  @Test
  void computeRunsOnTheComputePoolInInputOrder() {
    try (ExecutionContext context = ExecutionContext.open(parallel(), List.of())) {
      List<String> threads =
          context.compute(
              IntStream.range(0, 16).boxed().toList(), input -> Thread.currentThread().getName());
      assertThat(threads).allMatch(name -> name.startsWith("ForkJoinPool"));
      assertThat(context.compute(List.of(3, 1, 2), input -> input * 10))
          .containsExactly(30, 10, 20);
    }
  }

  @Test
  void tasksSeeTheScopedValuesOfTheSubmittingThread()
      throws InterruptedException, ExecutionException {
    try (ExecutionContext context = ExecutionContext.open(parallel(), List.of(TAG))) {
      ScopedValue.where(TAG, "run-7")
          .run(
              () -> {
                assertThat(context.fanOut(List.of(1, 2, 3), 3, input -> TAG.get()))
                    .containsOnly("run-7");
                assertThat(context.compute(List.of(1, 2, 3), input -> TAG.get()))
                    .containsOnly("run-7");
              });
      Future<String> future = ScopedValue.where(TAG, "ahead").call(() -> context.async(TAG::get));
      assertThat(future.get()).isEqualTo("ahead");
      assertThat(context.fanOut(List.of(1, 2), 2, input -> TAG.isBound())).containsOnly(false);
    }
  }

  @Test
  void closingTwiceIsHarmlessAndRejectsNewWork() {
    ExecutionContext context = ExecutionContext.open(parallel(), List.of());
    context.close();
    context.close();
    assertThatThrownBy(() -> context.fanOut(List.of(1), 1, input -> input))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> context.async(() -> 1)).isInstanceOf(IllegalStateException.class);
  }
}
