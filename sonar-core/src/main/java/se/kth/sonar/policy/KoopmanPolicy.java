package se.kth.sonar.policy;

import java.util.Objects;
import java.util.OptionalInt;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.search.Region;
import se.kth.sonar.state.RepairState;

/**
 * Allocates verifications by Koopman's index. With the search function {@code q(r, θ) = 1 - exp(-θ
 * r)}, the probability that the first {@code r} ranked candidates of a region that holds a repair
 * include one, the marginal return of a region with belief {@code b} after {@code r} candidates is
 * the index {@code I(r) = θ b exp(-θ r)}. The policy searches the region with the highest index and
 * verifies the fewest candidates after which its index falls to the initial index of the second
 * most probable region, {@code ceil(ln(b1 / b2) / θ)}, or all of them when no other region is left,
 * but never fewer than a minimum batch: adoption chooses among the improvements a batch verified,
 * and a batch too small to hold more than one settles for the first, which may be a workaround that
 * hides the evidence for the actual fault. It stops searching when the best index is at most {@code
 * 1 / (λ g)}, where {@code g} counts the violated specifications: further verification is then
 * expected to cost more than leaving them violated. See {@link BestRegionPolicy} for the other
 * steps.
 */
public final class KoopmanPolicy extends BestRegionPolicy {
  /** The value of θ unless a run chooses another; Jev's estimate on the benchmark. */
  public static final double DEFAULT_THETA = 0.73;

  /** The value of λ unless a run chooses another. */
  public static final double DEFAULT_LAMBDA = 100;

  private final Parameters parameters;
  private final int minimum;

  /**
   * The policy's parameters.
   *
   * @param theta how well the decision model ranks candidates, θ in {@code q(r, θ)}
   * @param lambda the cost of one violated specification left at the end, in verifications
   */
  public record Parameters(double theta, double lambda) {
    /** Checks the parameters. */
    public Parameters {
      if (!(theta > 0) || !Double.isFinite(theta)) {
        throw new IllegalArgumentException("θ must be positive and finite: " + theta);
      }
      if (!(lambda > 0)) {
        throw new IllegalArgumentException("λ must be positive: " + lambda);
      }
    }

    /** Returns the parameters used unless a run chooses others. */
    public static Parameters defaults() {
      return new Parameters(DEFAULT_THETA, DEFAULT_LAMBDA);
    }
  }

  /** Creates a policy with {@code parameters} that verifies at least {@code minimum} at a time. */
  public KoopmanPolicy(Parameters parameters, int minimum) {
    this(parameters, minimum, true);
  }

  /**
   * Creates a policy with {@code parameters} that verifies at least {@code minimum} at a time and
   * asks the decision model to assess regions if {@code assessing}.
   */
  public KoopmanPolicy(Parameters parameters, int minimum, boolean assessing) {
    super(assessing);
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    if (minimum < 1) {
      throw new IllegalArgumentException("the minimum batch must be at least 1: " + minimum);
    }
    this.minimum = minimum;
  }

  @Override
  OptionalInt candidates(RepairState state, Distribution<Region.Id> posterior, Region.Id best) {
    double theta = parameters.theta();
    double first = posterior.probability(best);
    int violated = state.verification().verdict().unresolved().size();
    if (theta * first <= 1 / (parameters.lambda() * violated)) {
      return OptionalInt.empty();
    }
    double second = 0;
    for (Region.Id other : posterior.outcomes()) {
      if (!other.equals(best)) {
        second = Math.max(second, posterior.probability(other));
      }
    }
    if (second <= 0) {
      return OptionalInt.of(Integer.MAX_VALUE);
    }
    double switching = Math.log(first / second) / theta;
    return OptionalInt.of(
        (int) Math.max(minimum, Math.min(Integer.MAX_VALUE, Math.ceil(switching))));
  }
}
