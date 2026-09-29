package se.kth.sonar.execution;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Gatherers;

/**
 * Applies a function to a list of inputs and returns the results in input order.
 *
 * <p>Failures are captured per input. After every call has finished, the failure of the earliest
 * failing input is rethrown unchanged, with later failures attached as suppressed exceptions, so a
 * parallel run fails with the same exception as the sequential run.
 */
final class FanOut {
  private FanOut() {}

  /** Runs the calls on the calling thread, stopping at the first failure. */
  static <T, R> List<R> sequential(
      List<? extends T> inputs, Function<? super T, ? extends R> call) {
    List<R> results = new ArrayList<>(inputs.size());
    for (T input : inputs) {
      results.add(call.apply(input));
    }
    return Collections.unmodifiableList(results);
  }

  /** Runs the calls on virtual threads with at most {@code limit} in flight. */
  static <T, R> List<R> concurrent(
      List<? extends T> inputs,
      int limit,
      Function<? super T, ? extends R> call,
      Optional<ScopedValue.Carrier> bindings) {
    List<Outcome<R>> outcomes =
        inputs.stream()
            .<Outcome<R>>gather(
                Gatherers.mapConcurrent(limit, input -> attempt(input, call, bindings)))
            .toList();
    return unwrap(outcomes);
  }

  /** Calls {@code call} on {@code input} under {@code bindings} and captures any failure. */
  static <T, R> Outcome<R> attempt(
      T input, Function<? super T, ? extends R> call, Optional<ScopedValue.Carrier> bindings) {
    try {
      R result =
          bindings.isPresent() ? bindings.get().call(() -> call.apply(input)) : call.apply(input);
      return new Outcome.Success<>(result);
    } catch (RuntimeException | Error failure) {
      return new Outcome.Failure<>(failure);
    }
  }

  /** Returns the results in order or rethrows the earliest failure. */
  static <R> List<R> unwrap(List<Outcome<R>> outcomes) {
    List<R> results = new ArrayList<>(outcomes.size());
    Throwable first = null;
    for (Outcome<R> outcome : outcomes) {
      switch (outcome) {
        case Outcome.Success<R>(R value) -> results.add(value);
        case Outcome.Failure<R>(Throwable failure) -> {
          if (first == null) {
            first = failure;
          } else if (!failure.equals(first)) {
            first.addSuppressed(failure);
          }
        }
      }
    }
    if (first != null) {
      throw rethrow(first);
    }
    return Collections.unmodifiableList(results);
  }

  /** Captures the current values of the bound keys among {@code keys}. */
  static Optional<ScopedValue.Carrier> capture(List<? extends ScopedValue<?>> keys) {
    Optional<ScopedValue.Carrier> carrier = Optional.empty();
    for (ScopedValue<?> key : keys) {
      if (key.isBound()) {
        carrier = Optional.of(bind(carrier, key));
      }
    }
    return carrier;
  }

  private static <V> ScopedValue.Carrier bind(
      Optional<ScopedValue.Carrier> carrier, ScopedValue<V> key) {
    V value = key.get();
    return carrier.map(c -> c.where(key, value)).orElseGet(() -> ScopedValue.where(key, value));
  }

  private static RuntimeException rethrow(Throwable failure) {
    if (failure instanceof RuntimeException runtime) {
      return runtime;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    return new UndeclaredThrowableException(failure);
  }

  /** The result of one call. */
  sealed interface Outcome<R> {
    /** A call that returned {@code value}. */
    record Success<R>(R value) implements Outcome<R> {}

    /** A call that threw {@code failure}. */
    record Failure<R>(Throwable failure) implements Outcome<R> {}
  }
}
