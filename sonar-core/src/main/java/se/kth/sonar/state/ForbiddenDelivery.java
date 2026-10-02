package se.kth.sonar.state;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Finds the objects behind a delivery that must not be: the session policies that let its route
 * through, and what originates a destination that nobody is to reach.
 */
public final class ForbiddenDelivery {
  private ForbiddenDelivery() {}

  /**
   * Counts, for every policy object, the violated obligations that forbid a delivery whose path
   * crosses the object's external BGP session.
   *
   * <p>The flow is delivered because every router on its path holds a route, and each router got
   * that route from the next one. Where two consecutive routers are in different autonomous
   * systems, the next one's export policy and this one's import policy admitted the route, so their
   * {@link SessionPolicy} slice can withhold it. A crossing that a satisfied obligation needs for
   * the same destination is not one: another source's traffic must be delivered over it, so the
   * route has to cross there. Each distinct violated predicate counts once per object.
   */
  public static Map<ProgramObject.Ref, Integer> policies(RepairState state) {
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<Crossing> required = requiredCrossings(state);
    Map<ProgramObject.Ref, Integer> counts = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      if (!specification.forbidsDelivery() || !seen.add(specification.predicate().key())) {
        continue;
      }
      Set<ProgramObject.Ref> observation = new LinkedHashSet<>();
      for (DataPlaneEvidence.Trace trace :
          evidence.traces().getOrDefault(specification.predicate(), List.of())) {
        if (trace.disposition() != DataPlaneEvidence.Disposition.ACCEPTED) {
          continue;
        }
        List<RouterId> hops = trace.hops();
        Prefix destination = specification.predicate().destination();
        for (int at = 0; at + 1 < hops.size(); at++) {
          if (!required.contains(new Crossing(destination, hops.get(at), hops.get(at + 1)))) {
            SessionPolicy.add(
                state.program(), hops.get(at), hops.get(at + 1), destination, observation);
          }
        }
      }
      observation.forEach(object -> counts.merge(object, 1, Integer::sum));
    }
    return Map.copyOf(counts);
  }

  /**
   * Counts, for every object that originates a destination, the violated obligations that forbid a
   * delivery to it, when no obligation requires a delivery to that destination from anywhere. Such
   * a destination serves nobody, so what originates it is itself in doubt: an interface on a wrong
   * subnet, or a route that should not be announced. Each distinct violated predicate counts once
   * per object.
   */
  public static Map<ProgramObject.Ref, Integer> origins(RepairState state) {
    Set<Prefix> served = new HashSet<>();
    for (Specification specification : state.problem().specifications().all()) {
      if (specification.requiresDelivery()) {
        served.add(specification.predicate().destination());
      }
    }
    RouterEvidence evidence = RouterEvidence.of(state);
    Map<ProgramObject.Ref, Integer> counts = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      Prefix destination = specification.predicate().destination();
      if (specification.forbidsDelivery()
          && !served.contains(destination)
          && seen.add(specification.predicate().key())) {
        evidence.origins(destination).forEach(origin -> counts.merge(origin, 1, Integer::sum));
      }
    }
    return Map.copyOf(counts);
  }

  private record Crossing(Prefix destination, RouterId receiver, RouterId sender) {}

  /** Returns the hops over which a satisfied obligation has its destination delivered. */
  private static Set<Crossing> requiredCrossings(RepairState state) {
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<Specification> unresolved = new HashSet<>(state.verification().verdict().unresolved());
    Set<Crossing> crossings = new HashSet<>();
    for (Specification specification : state.problem().specifications().all()) {
      if (!specification.requiresDelivery() || unresolved.contains(specification)) {
        continue;
      }
      for (DataPlaneEvidence.Trace trace :
          evidence.traces().getOrDefault(specification.predicate(), List.of())) {
        if (trace.disposition() != DataPlaneEvidence.Disposition.ACCEPTED) {
          continue;
        }
        List<RouterId> hops = trace.hops();
        for (int at = 0; at + 1 < hops.size(); at++) {
          crossings.add(
              new Crossing(
                  specification.predicate().destination(), hops.get(at), hops.get(at + 1)));
        }
      }
    }
    return crossings;
  }
}
