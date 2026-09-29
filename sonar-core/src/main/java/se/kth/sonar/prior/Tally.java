package se.kth.sonar.prior;

/**
 * Counts of successes and failures of a binary outcome, with estimates under the Jeffreys prior,
 * which adds one half to each count.
 *
 * @param successes the number of successes
 * @param failures the number of failures
 */
public record Tally(long successes, long failures) {
  private static final double HALF = 0.5;

  /** Checks that the counts are not negative. */
  public Tally {
    if (successes < 0 || failures < 0) {
      throw new IllegalArgumentException("counts must not be negative");
    }
  }

  /** Returns a tally with no observations. */
  public static Tally empty() {
    return new Tally(0, 0);
  }

  /** Returns this tally with one more observation. */
  public Tally plus(boolean success) {
    return success ? new Tally(successes + 1, failures) : new Tally(successes, failures + 1);
  }

  /** Returns the estimated probability of success, {@code (s + 1/2) / (s + f + 1)}. */
  public double probability() {
    return ((double) successes + HALF) / ((double) successes + (double) failures + 1);
  }

  /** Returns the estimated odds of success, {@code (s + 1/2) / (f + 1/2)}. */
  public double odds() {
    return ((double) successes + HALF) / ((double) failures + HALF);
  }
}
