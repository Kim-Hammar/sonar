package se.kth.sonar.prior;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.search.Region;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.text.RouterId;

/**
 * Weights a region by how strongly the verifier's evidence implicates the routers it edits.
 *
 * <p>Every specification, violated or satisfied, implicates the routers of its evidence. A router
 * covered by {@code f} violated and {@code p} satisfied specifications out of {@code F} violated
 * ones has the Ochiai suspiciousness {@code sqrt(P(violated | covers) * P(covers | violated))},
 * with both probabilities taken as posterior means under uniform priors: {@code sqrt((f + 1) / (f +
 * p + 2) * (f + 1) / (F + 2))}. No router gets zero, so the factor lowers but never removes a
 * region. A region weighs the sum over the routers it may edit.
 */
public final class SpectrumFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;

  private final ContentCache<Map<RouterId, Double>> suspiciousness =
      new ContentCache<>(CACHE_CAPACITY);

  @Override
  public String name() {
    return "spectrum";
  }

  @Override
  public double weight(Region region, RepairState state) {
    Map<RouterId, Double> scores =
        suspiciousness.get(state.verification().digest(), () -> suspiciousness(state));
    double weight = 0;
    for (RouterId router : region.routers()) {
      weight += scores.getOrDefault(router, 0.0);
    }
    return weight;
  }

  /** Returns the suspiciousness of every router of {@code state}'s network. */
  static Map<RouterId, Double> suspiciousness(RepairState state) {
    RouterEvidence evidence = RouterEvidence.of(state);
    Map<RouterId, Integer> failed = new HashMap<>();
    Map<RouterId, Integer> passed = new HashMap<>();
    Set<Specification> unresolved = new HashSet<>(state.verification().verdict().unresolved());
    int violated = unresolved.size();
    for (Specification specification : state.problem().specifications().all()) {
      Map<RouterId, Integer> counts = unresolved.contains(specification) ? failed : passed;
      for (RouterId router : evidence.routers(specification)) {
        counts.merge(router, 1, Integer::sum);
      }
    }
    Map<RouterId, Double> scores = new HashMap<>();
    for (RouterId router : state.snapshot().routers()) {
      double f = failed.getOrDefault(router, 0);
      double p = passed.getOrDefault(router, 0);
      scores.put(router, Math.sqrt((f + 1) / (f + p + 2) * (f + 1) / (violated + 2)));
    }
    return Map.copyOf(scores);
  }
}
