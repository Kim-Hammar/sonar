package se.kth.sonar.prior;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.search.Region;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Weights a region by the flows dropped on its routers.
 *
 * <p>When a violated flow is denied or discarded, the router that dropped it is where the evidence
 * ends, unlike the routers it merely passed. Each distinct observation, one per source and
 * destination, counts once for the router at the end of its trace. A region editing a router that
 * dropped {@code n} observations weighs the Jeffreys odds {@code (n + 1/2) / (1/2)}; other regions
 * weigh one.
 */
public final class DropFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private static final double HALF = 0.5;
  private static final Set<DataPlaneEvidence.Disposition> DROPS =
      Set.of(
          DataPlaneEvidence.Disposition.DENIED,
          DataPlaneEvidence.Disposition.NULL_ROUTED,
          DataPlaneEvidence.Disposition.NEIGHBOR_UNREACHABLE);

  private final ContentCache<Map<RouterId, Integer>> drops = new ContentCache<>(CACHE_CAPACITY);

  @Override
  public double weight(Region region, RepairState state) {
    Map<RouterId, Integer> counts = drops.get(state.verification().digest(), () -> drops(state));
    double weight = 1;
    for (RouterId router : region.routers()) {
      Integer count = counts.get(router);
      if (count != null) {
        weight = Math.max(weight, (count + HALF) / HALF);
      }
    }
    return weight;
  }

  /** Counts, for every router, the distinct violated observations it dropped. */
  private static Map<RouterId, Integer> drops(RepairState state) {
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<List<Object>> observed = new HashSet<>();
    Map<RouterId, Integer> counts = new HashMap<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      if (predicate instanceof ForwardingPredicate.Isolation) {
        continue;
      }
      for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
        if (DROPS.contains(trace.disposition()) && trace.last().isPresent()) {
          RouterId last = trace.last().get();
          if (observed.add(List.of(predicate.source(), predicate.destination(), last))) {
            counts.merge(last, 1, Integer::sum);
          }
        }
      }
    }
    return Map.copyOf(counts);
  }
}
