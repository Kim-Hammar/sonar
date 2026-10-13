package se.kth.sonar.app.theta;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The estimate of θ in the search function {@code q(r, θ) = 1 - exp(-θ r)}, the probability that a
 * valid repair is among the first {@code r} candidates the agent selects from a region that holds
 * one.
 *
 * <p>For whole numbers {@code r}, {@code q} makes the rank of the first valid repair geometric with
 * success probability {@code p = 1 - exp(-θ)}, so the maximum-likelihood estimate from {@code N}
 * ranks summing to {@code S} is {@code ln(S / (S - N))}. It is infinite when every rank is one. The
 * confidence interval is the percentile interval of the estimate over bootstrap resamples.
 *
 * @param theta the maximum-likelihood estimate
 * @param low the lower end of the 95% confidence interval
 * @param high the upper end of the 95% confidence interval
 * @param episodes the ranks the estimate rests on
 */
public record ThetaEstimate(double theta, double low, double high, int episodes) {
  private static final int RESAMPLES = 2_000;

  /**
   * Estimates θ from {@code ranks}, resampling with {@code seed}.
   *
   * @throws IllegalArgumentException if there are no ranks or a rank is below one
   */
  public static ThetaEstimate of(List<Integer> ranks, long seed) {
    if (ranks.isEmpty()) {
      throw new IllegalArgumentException("θ needs at least one rank");
    }
    int[] values = ranks.stream().mapToInt(Integer::intValue).toArray();
    for (int value : values) {
      if (value < 1) {
        throw new IllegalArgumentException("ranks count from one: " + value);
      }
    }
    Random random = new Random(seed);
    double[] resampled = new double[RESAMPLES];
    int[] draw = new int[values.length];
    for (int index = 0; index < RESAMPLES; index++) {
      for (int position = 0; position < draw.length; position++) {
        draw[position] = values[random.nextInt(values.length)];
      }
      resampled[index] = mle(draw);
    }
    Arrays.sort(resampled);
    return new ThetaEstimate(
        mle(values),
        resampled[(int) Math.floor(0.025 * (RESAMPLES - 1))],
        resampled[(int) Math.ceil(0.975 * (RESAMPLES - 1))],
        values.length);
  }

  private static double mle(int[] ranks) {
    long sum = 0;
    for (int rank : ranks) {
      sum += rank;
    }
    return sum == ranks.length
        ? Double.POSITIVE_INFINITY
        : Math.log((double) sum / (sum - ranks.length));
  }
}
