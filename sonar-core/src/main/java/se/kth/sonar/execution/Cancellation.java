package se.kth.sonar.execution;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A request to stop a run, shared by every task of the run. Stopping is cooperative: the engine and
 * the benchmark runner check the token between steps and wind down cleanly.
 */
public final class Cancellation {
  private final AtomicBoolean cancelled = new AtomicBoolean();

  /** Asks the run to stop; asking again has no further effect. */
  public void cancel() {
    cancelled.set(true);
  }

  /** Returns whether the run was asked to stop. */
  public boolean isCancelled() {
    return cancelled.get();
  }

  /** Throws {@link CancellationException} if the run was asked to stop. */
  public void checkNotCancelled() {
    if (isCancelled()) {
      throw new CancellationException("the run was cancelled");
    }
  }
}
