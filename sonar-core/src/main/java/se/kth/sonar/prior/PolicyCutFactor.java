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
 * factor deliberately does not multiply by the number of unrelated program objects: observing a cut
 * localises a possible cause, but does not establish that the cut rather than another competing
 * relation caused the missing route.
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
    return 2.0 * count + 1.0;
  }
}
