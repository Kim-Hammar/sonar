package se.kth.sonar.state;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * A verifier-observed route propagation boundary at which one route map can transfer routes between
 * BGP and the IGP.
 *
 * <p>The analysis joins only formal facts: a violated destination, its route holders or accepted
 * path, a propagation-boundary router running both protocols, and typed route-map semantics. It
 * neither uses names nor benchmark labels.
 *
 * @param policy the route map at the protocol boundary
 * @param targets the violated specifications whose destinations reach that boundary
 * @param blockers deny clauses that can intercept at least one target before a permit path
 */
public record ProtocolTransfer(
    ProgramObject.Ref policy, List<Specification> targets, List<ProgramObject.Ref> blockers) {

  /** Copies and checks the components. */
  public ProtocolTransfer {
    Objects.requireNonNull(policy, "policy");
    if (!policy.type().equals("route-map")) {
      throw new IllegalArgumentException("a protocol transfer policy is a route map: " + policy);
    }
    targets = List.copyOf(targets);
    blockers = List.copyOf(blockers);
  }

  /** Infers all protocol transfers that can affect currently violated specifications. */
  public static List<ProtocolTransfer> infer(RepairState state) {
    RouterEvidence routerEvidence = RouterEvidence.of(state);
    DataPlaneEvidence evidence = state.verification().evidence();
    Map<ProgramObject.Ref, LinkedHashSet<Specification>> targets = new LinkedHashMap<>();
    Map<ProgramObject.Ref, LinkedHashSet<ProgramObject.Ref>> blockers = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      if (!seen.add(predicate.source() + "|" + predicate.destination())) {
        continue;
      }
      Set<RouterId> candidates = boundaryRouters(routerEvidence, evidence, predicate);
      for (RouterId router : candidates) {
        ProgramObject owner =
            state
                .program()
                .object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()))
                .orElse(null);
        if (owner == null || !owner.has("bgp-asn") || !owner.has("ospf-process")) {
          continue;
        }
        for (ProgramObject policy : state.program().objectsOf(router)) {
          if (!policy.ref().type().equals("route-map")) {
            continue;
          }
          List<ProgramObject> possible =
              RouteMapEvaluation.possibleBlockers(
                  state.program(), policy.ref(), predicate.destination());
          if (!possible.isEmpty()
              || (!policy.has("used")
                  && RouteMapEvaluation.permits(
                      state.program(), policy.ref(), predicate.destination()))) {
            targets
                .computeIfAbsent(policy.ref(), ignored -> new LinkedHashSet<>())
                .add(specification);
            LinkedHashSet<ProgramObject.Ref> refs =
                blockers.computeIfAbsent(policy.ref(), ignored -> new LinkedHashSet<>());
            possible.forEach(entry -> refs.add(entry.ref()));
          }
        }
      }
    }
    List<ProtocolTransfer> result = new ArrayList<>();
    targets.forEach(
        (policy, specifications) ->
            result.add(
                new ProtocolTransfer(
                    policy,
                    List.copyOf(specifications),
                    List.copyOf(blockers.getOrDefault(policy, new LinkedHashSet<>())))));
    return List.copyOf(result);
  }

  private static Set<RouterId> boundaryRouters(
      RouterEvidence routerEvidence, DataPlaneEvidence evidence, ForwardingPredicate predicate) {
    Set<RouterId> holders = evidence.routeHolders().getOrDefault(predicate.destination(), Set.of());
    Set<RouterId> candidates = new LinkedHashSet<>();
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
    return Set.copyOf(candidates);
  }
}
