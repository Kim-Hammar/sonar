package se.kth.sonar.prior;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Weights regions that span an exact relation named by a formal counterexample.
 *
 * <p>For an isolation violation, the first forwarding edge is a concrete forbidden relation. For a
 * waypoint violation, the source and required waypoint are operands of the obligation itself. A
 * no-route trace that stops at its source is an exact observation about that router's local route
 * program. For a path-diversity deficit, an unused physical next hop that already holds the route
 * identifies a possible protocol-transfer boundary; typed route-map evaluation then retains only
 * policies that permit that destination. Each distinct violated predicate contributes one
 * observation. A matching region with {@code n} observations receives the Jeffreys odds {@code (n +
 * 1/2) / (1/2)}; unrelated regions retain weight one. This is deliberately narrower than topology
 * proximity: it uses only relations present in the specification or observed by the verifier.
 */
public final class CounterexampleFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private static final double HALF = 0.5;

  private final ContentCache<Evidence> observations = new ContentCache<>(CACHE_CAPACITY);

  private record Evidence(
      Map<Pair, Integer> relations,
      Map<RouterId, Integer> noRouteSources,
      Map<ProgramObject.Ref, Integer> transfers) {}

  private record Pair(RouterId first, RouterId second) {
    static Pair of(RouterId left, RouterId right) {
      return left.compareTo(right) <= 0 ? new Pair(left, right) : new Pair(right, left);
    }
  }

  @Override
  public String name() {
    return "counterexample";
  }

  @Override
  public double weight(Region region, RepairState state) {
    Evidence evidence = observations.get(state.verification().digest(), () -> observations(state));
    double weight = 1;
    if (region instanceof SiteRegion site && site.site().type().equals(ObjectType.ROUTER)) {
      int count = evidence.noRouteSources().getOrDefault(site.site().router(), 0);
      if (count > 0) {
        weight = (count + HALF) / HALF;
      }
    }
    if (region instanceof RelationRegion relation
        && relation.instance().relation().equals("route-map-liveness")) {
      ProgramObject.Ref routeMap = relation.instance().roles().get("map");
      int count = evidence.transfers().getOrDefault(routeMap, 0);
      if (count > 0) {
        long universe = state.program().objects("route-map").size();
        double reduction = (double) universe / evidence.transfers().size();
        weight = Math.max(weight, reduction * (count + HALF) / HALF);
      }
    }
    if (region instanceof TransferRegion transfer) {
      int count =
          (int)
              transfer.transfer().targets().stream()
                  .map(target -> target.predicate().key())
                  .distinct()
                  .count();
      if (count > 0) {
        weight = Math.max(weight, (count + HALF) / HALF);
      }
    }
    if (region.routers().size() < 2) {
      return weight;
    }
    for (Map.Entry<Pair, Integer> entry : evidence.relations().entrySet()) {
      Pair pair = entry.getKey();
      if (region.routers().contains(pair.first()) && region.routers().contains(pair.second())) {
        weight = Math.max(weight, (entry.getValue() + HALF) / HALF);
      }
    }
    return weight;
  }

  private static Evidence observations(RepairState state) {
    Map<Pair, Integer> relations = new HashMap<>();
    Map<RouterId, Integer> noRouteSources = new HashMap<>();
    Map<ProgramObject.Ref, Integer> transfers = new HashMap<>();
    Set<String> seen = new HashSet<>();
    Set<String> seenTransfers = new HashSet<>();
    DataPlaneEvidence evidence = state.verification().evidence();
    RouterEvidence routerEvidence = RouterEvidence.of(state);
    for (Specification specification : state.verification().verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      if (!seen.add(predicate.key())) {
        continue;
      }
      if (RouterEvidence.noRouteAtSource(evidence, predicate)) {
        noRouteSources.merge(predicate.source(), 1, Integer::sum);
      }
      if (predicate instanceof ForwardingPredicate.Waypointing waypointing) {
        boolean observed =
            evidence.traces().getOrDefault(predicate, List.of()).stream()
                .anyMatch(trace -> trace.hops().contains(waypointing.waypoint()));
        if (!observed && !waypointing.source().equals(waypointing.waypoint())) {
          relations.merge(Pair.of(waypointing.source(), waypointing.waypoint()), 1, Integer::sum);
        }
      } else if (predicate instanceof ForwardingPredicate.Isolation) {
        for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
          if (trace.hops().size() >= 2) {
            relations.merge(Pair.of(trace.hops().get(0), trace.hops().get(1)), 1, Integer::sum);
          }
        }
      }
      String transferKey = predicate.source() + "|" + predicate.destination();
      if (seenTransfers.add(transferKey)) {
        countTransfers(state, routerEvidence, predicate, transfers);
      }
    }
    return new Evidence(Map.copyOf(relations), Map.copyOf(noRouteSources), Map.copyOf(transfers));
  }

  /** Joins one forwarding observation to exact BGP-to-OSPF policy uses. */
  private static void countTransfers(
      RepairState state,
      RouterEvidence routerEvidence,
      ForwardingPredicate predicate,
      Map<ProgramObject.Ref, Integer> transfers) {
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<RouterId> holders = evidence.routeHolders().getOrDefault(predicate.destination(), Set.of());
    Set<RouterId> candidates = new HashSet<>();
    if (RouterEvidence.noRouteAtSource(evidence, predicate)) {
      for (RouterEvidence.Link frontier :
          routerEvidence.routeFrontier(predicate.source(), predicate.destination())) {
        if (holders.contains(frontier.first())) {
          candidates.add(frontier.first());
        }
        if (holders.contains(frontier.second())) {
          candidates.add(frontier.second());
        }
      }
    }
    if (predicate instanceof ForwardingPredicate.Waypointing waypointing) {
      for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
        if (trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED
            && !trace.hops().contains(waypointing.waypoint())) {
          trace.hops().stream().filter(holders::contains).forEach(candidates::add);
        }
      }
    }
    if (predicate instanceof ForwardingPredicate.LoadBalancing loadBalancing) {
      Set<RouterId> observedNextHops = new HashSet<>();
      for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
        if (trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED
            && trace.hops().size() >= 2
            && trace.hops().getFirst().equals(loadBalancing.source())) {
          observedNextHops.add(trace.hops().get(1));
        }
      }
      if (observedNextHops.size() < loadBalancing.routes()) {
        state.problem().topology().neighbours(loadBalancing.source()).stream()
            .filter(holders::contains)
            .filter(router -> !observedNextHops.contains(router))
            .forEach(candidates::add);
      }
    }
    for (RouterId router : candidates) {
      ProgramObject owner =
          state
              .program()
              .object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()))
              .orElse(null);
      if (owner == null || !owner.has("bgp-asn") || !owner.has("ospf-process")) {
        continue;
      }
      state.program().objectsOf(router).stream()
          .filter(object -> object.ref().type().equals("route-map"))
          .filter(
              object ->
                  RouteMapEvaluation.permits(
                      state.program(), object.ref(), predicate.destination()))
          .forEach(object -> transfers.merge(object.ref(), 1, Integer::sum));
    }
  }
}
