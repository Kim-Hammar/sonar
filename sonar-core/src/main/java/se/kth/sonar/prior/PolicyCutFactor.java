package se.kth.sonar.prior;

import java.util.Map;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.state.PolicyCut;
import se.kth.sonar.state.RepairState;

/**
 * Weights exact outbound-policy cuts obtained by joining verifier and typed-program evidence.
 *
 * <p>Each distinct no-route observation on the exact policy cut contributes one Bernoulli success.
 * Against the neutral zero-observation case, the Jeffreys odds are {@code (n + 1/2) / (1/2)}. The
 * verifier/program join reduces a uniform prefix-list universe to the lists on the observed cut, so
 * that reduction is the likelihood ratio. This is the same finite-model Bayes factor used for other
 * exact counterexample relations; competing relations retain their own independent factors.
 */
public final class PolicyCutFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;

  private final ContentCache<Map<ProgramObject.Ref, Integer>> cuts =
      new ContentCache<>(CACHE_CAPACITY);

  @Override
  public String name() {
    return "policy-cut";
  }

  @Override
  public double weight(Region region, RepairState state) {
    if (!(region instanceof SiteRegion site)) {
      return 1;
    }
    Map<ProgramObject.Ref, Integer> observations =
        cuts.get(state.verification().digest(), () -> PolicyCut.missingPermits(state));
    Integer count = observations.get(site.site());
    if (count == null) {
      return 1;
    }
    long universe = state.program().objects(site.site().type()).size();
    long alternatives =
        observations.keySet().stream().filter(ref -> ref.type().equals(site.site().type())).count();
    double reduction = Math.max(1.0, (double) universe / Math.max(1, alternatives));
    return reduction * (2.0 * count + 1.0);
  }
}
