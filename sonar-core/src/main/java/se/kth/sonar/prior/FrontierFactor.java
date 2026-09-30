package se.kth.sonar.prior;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.search.Region;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Weights a region by the route-propagation frontiers it spans.
 *
 * <p>When a source has no route to a destination, the routers without a forwarding entry for it
 * form a connected component around the source, and that component ends exactly at the physical
 * links to routers that do have an entry or originate the destination. Each distinct no-route
 * observation, one per source and destination, counts once for every frontier link it reaches. A
 * region whose routers include both ends of a link reached by {@code n} observations weighs the
 * Jeffreys odds {@code (n + 1/2) / (1/2)}; other regions weigh one.
 */
public final class FrontierFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private static final double HALF = 0.5;

  private final ContentCache<Map<RouterEvidence.Link, Integer>> frontiers =
      new ContentCache<>(CACHE_CAPACITY);

  @Override
  public String name() {
    return "frontier";
  }

  @Override
  public double weight(Region region, RepairState state) {
    Map<RouterEvidence.Link, Integer> counts =
        frontiers.get(state.verification().digest(), () -> frontiers(state));
    NavigableSet<RouterId> routers = region.routers();
    if (routers.size() < 2) {
      return 1;
    }
    double weight = 1;
    for (Map.Entry<RouterEvidence.Link, Integer> entry : counts.entrySet()) {
      RouterEvidence.Link link = entry.getKey();
      if (routers.contains(link.first()) && routers.contains(link.second())) {
        weight = Math.max(weight, (entry.getValue() + HALF) / HALF);
      }
    }
    return weight;
  }

  /** Counts, for every frontier link, the no-route observations that reach it. */
  private static Map<RouterEvidence.Link, Integer> frontiers(RepairState state) {
    RouterEvidence owners = RouterEvidence.of(state);
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<List<Object>> observed = new HashSet<>();
    Map<RouterEvidence.Link, Integer> counts = new HashMap<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      if (!RouterEvidence.noRouteAtSource(evidence, predicate)
          || !observed.add(List.of(predicate.source(), predicate.destination()))) {
        continue;
      }
      for (RouterEvidence.Link link :
          owners.routeFrontier(predicate.source(), predicate.destination())) {
        counts.merge(link, 1, Integer::sum);
      }
    }
    return Map.copyOf(counts);
  }
}
