package se.kth.sonar.app;

import java.util.Locale;

/** Where a run is in its life. */
public enum RunStatus {
  QUEUED,
  RUNNING,
  FINISHED,
  FAILED,
  CANCELLED,
  INTERRUPTED;

  /** Returns the lower-case label used in run logs and the API. */
  public String label() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Returns whether the run has ended. */
  public boolean terminal() {
    return this != QUEUED && this != RUNNING;
  }

  /** Returns the status with {@code label}. */
  public static RunStatus parse(String label) {
    return valueOf(label.toUpperCase(Locale.ROOT));
  }
}
