package se.kth.sonar.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/** Finds typed policy objects on an observed route-propagation cut. */
public final class PolicyCut {
  private PolicyCut() {}

  /**
   * Counts no-route observations for which each prefix list is on the exact outbound BGP policy
   * path from a route holder across the nearest propagation frontier and does not permit the
   * destination.
   */
  public static Map<ProgramObject.Ref, Integer> missingPermits(RepairState state) {
    Map<ProgramObject.Ref, Integer> counts = new LinkedHashMap<>();
    analyze(state)
        .forEach(
            (cut, observations) ->
                counts.put(cut, observations.values().stream().mapToInt(Integer::intValue).sum()));
    return Map.copyOf(counts);
  }

  /** Returns the exact destination prefixes missing from each prefix list on the policy cut. */
  public static Map<ProgramObject.Ref, Set<Prefix>> missingPermitTargets(RepairState state) {
    Map<ProgramObject.Ref, Set<Prefix>> targets = new LinkedHashMap<>();
    analyze(state)
        .forEach((cut, observations) -> targets.put(cut, Set.copyOf(observations.keySet())));
    return Map.copyOf(targets);
  }

  private static Map<ProgramObject.Ref, Map<Prefix, Integer>> analyze(RepairState state) {
    RouterEvidence routerEvidence = RouterEvidence.of(state);
    DataPlaneEvidence evidence = state.verification().evidence();
    Map<ProgramObject.Ref, Map<Prefix, Integer>> cuts = new HashMap<>();
    Set<String> seen = new HashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      if (!RouterEvidence.noRouteAtSource(evidence, predicate)
          || !seen.add(predicate.source() + "|" + predicate.destination())) {
        continue;
      }
      Set<RouterId> holders =
          evidence.routeHolders().getOrDefault(predicate.destination(), Set.of());
      Set<ProgramObject.Ref> observation = new LinkedHashSet<>();
      for (RouterEvidence.Link link :
          routerEvidence.routeFrontier(predicate.source(), predicate.destination())) {
        if (holders.contains(link.first())) {
          addOutboundCuts(state, link.first(), link.second(), predicate.destination(), observation);
        }
        if (holders.contains(link.second())) {
          addOutboundCuts(state, link.second(), link.first(), predicate.destination(), observation);
        }
      }
      observation.forEach(
          cut ->
              cuts.computeIfAbsent(cut, ignored -> new HashMap<>())
                  .merge(predicate.destination(), 1, Integer::sum));
    }
    return Map.copyOf(cuts);
  }

  private static void addOutboundCuts(
      RepairState state,
      RouterId holder,
      RouterId peer,
      Prefix destination,
      Set<ProgramObject.Ref> cuts) {
    ProgramObject.Ref peerRef = new ProgramObject.Ref("router", peer, List.of());
    for (ProgramObject neighbor : state.program().objectsOf(holder)) {
      if (!neighbor.ref().type().equals("bgp-neighbor")
          || neighbor.value("peer-router").filter(peerRef.toString()::equals).isEmpty()) {
        continue;
      }
      ProgramObject.Ref routeMap =
          neighbor
              .value("route-map-out")
              .flatMap(value -> ref(value, "route-map", holder))
              .orElse(null);
      if (routeMap == null || routeMap.key().isEmpty()) {
        continue;
      }
      String name = routeMap.key().getFirst();
      for (ProgramObject entry : state.program().objectsOf(holder)) {
        if (!entry.ref().type().equals("route-map-entry")
            || !entry.ref().key().getFirst().equals(name)
            || entry.value("action").filter("permit"::equals).isEmpty()) {
          continue;
        }
        for (String value : entry.values("match-prefix-list")) {
          ref(value, "prefix-list", holder)
              .filter(
                  list -> !RouteMapEvaluation.prefixListPermits(state.program(), list, destination))
              .ifPresent(cuts::add);
        }
      }
    }
  }

  private static java.util.Optional<ProgramObject.Ref> ref(
      String value, String type, RouterId router) {
    if (!value.contains(":")) {
      return value.isBlank()
          ? java.util.Optional.empty()
          : java.util.Optional.of(new ProgramObject.Ref(type, router, List.of(value)));
    }
    try {
      ProgramObject.Ref ref = ProgramObject.Ref.parse(value);
      return ref.type().equals(type) ? java.util.Optional.of(ref) : java.util.Optional.empty();
    } catch (IllegalArgumentException malformed) {
      return java.util.Optional.empty();
    }
  }
}
