package se.kth.sonar.prior;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.network.Topology;
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

  private final ContentCache<Map<Link, Integer>> frontiers = new ContentCache<>(CACHE_CAPACITY);

  record Link(RouterId first, RouterId second) {
    static Link of(RouterId a, RouterId b) {
      return a.compareTo(b) <= 0 ? new Link(a, b) : new Link(b, a);
    }
  }

  @Override
  public double weight(Region region, RepairState state) {
    Map<Link, Integer> counts =
        frontiers.get(state.verification().digest(), () -> frontiers(state));
    NavigableSet<RouterId> routers = region.routers();
    if (routers.size() < 2) {
      return 1;
    }
    double weight = 1;
    for (Map.Entry<Link, Integer> entry : counts.entrySet()) {
      Link link = entry.getKey();
      if (routers.contains(link.first()) && routers.contains(link.second())) {
        weight = Math.max(weight, (entry.getValue() + HALF) / HALF);
      }
    }
    return weight;
  }

  /** Counts, for every frontier link, the no-route observations that reach it. */
  private static Map<Link, Integer> frontiers(RepairState state) {
    RouterEvidence owners = RouterEvidence.of(state);
    DataPlaneEvidence evidence = state.verification().evidence();
    Topology topology = state.problem().topology();
    Set<List<Object>> observed = new HashSet<>();
    Map<Link, Integer> counts = new HashMap<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      if (!noRouteAtSource(evidence, predicate)
          || !observed.add(List.of(predicate.source(), predicate.destination()))) {
        continue;
      }
      Set<RouterId> knowing = new HashSet<>(owners.owners(predicate.destination()));
      knowing.addAll(evidence.forwarders().getOrDefault(predicate.destination(), Set.of()));
      for (Link link : frontier(predicate.source(), knowing, topology)) {
        counts.merge(link, 1, Integer::sum);
      }
    }
    return Map.copyOf(counts);
  }

  private static boolean noRouteAtSource(
      DataPlaneEvidence evidence, ForwardingPredicate predicate) {
    List<DataPlaneEvidence.Trace> traces = evidence.traces().get(predicate);
    if (traces == null || traces.isEmpty()) {
      return false;
    }
    for (DataPlaneEvidence.Trace trace : traces) {
      if (trace.disposition() != DataPlaneEvidence.Disposition.NO_ROUTE
          || trace.hops().size() != 1) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the links where the no-route component around {@code source} meets a knowing router.
   */
  static Set<Link> frontier(RouterId source, Set<RouterId> knowing, Topology topology) {
    Set<Link> frontier = new HashSet<>();
    if (knowing.contains(source)) {
      return frontier;
    }
    Set<RouterId> seen = new HashSet<>(Set.of(source));
    Deque<RouterId> queue = new ArrayDeque<>(List.of(source));
    while (!queue.isEmpty()) {
      RouterId router = queue.removeFirst();
      for (RouterId peer : topology.neighbours(router)) {
        if (knowing.contains(peer)) {
          frontier.add(Link.of(router, peer));
        } else if (seen.add(peer)) {
          queue.addLast(peer);
        }
      }
    }
    return frontier;
  }
}
