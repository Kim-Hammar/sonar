package se.kth.sonar.state;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * @param attachment the exact missing BGP policy attachment observed on a forbidden forwarding
 *     edge, when this transfer represents one
 */
public record ProtocolTransfer(
    ProgramObject.Ref policy,
    List<Specification> targets,
    List<ProgramObject.Ref> blockers,
    Optional<Attachment> attachment) {

  private record AttachmentKey(ProgramObject.Ref policy, Attachment attachment) {}

  /** Copies and checks the components. */
  public ProtocolTransfer {
    Objects.requireNonNull(policy, "policy");
    if (!policy.type().equals("route-map")) {
      throw new IllegalArgumentException("a protocol transfer policy is a route map: " + policy);
    }
    targets = List.copyOf(targets);
    blockers = List.copyOf(blockers);
    Objects.requireNonNull(attachment, "attachment");
  }

  /** Creates an ordinary protocol-boundary transfer without an exact BGP attachment. */
  public ProtocolTransfer(
      ProgramObject.Ref policy, List<Specification> targets, List<ProgramObject.Ref> blockers) {
    this(policy, targets, blockers, Optional.empty());
  }

  /**
   * One exact neighbor-policy attachment.
   *
   * @param neighbor the typed BGP neighbor object
   * @param direction {@code in} at the receiver or {@code out} at the sender
   */
  public record Attachment(ProgramObject.Ref neighbor, String direction) {
    /** Checks the typed neighbor and direction. */
    public Attachment {
      Objects.requireNonNull(neighbor, "neighbor");
      Objects.requireNonNull(direction, "direction");
      if (!neighbor.type().equals("bgp-neighbor")) {
        throw new IllegalArgumentException("a policy attachment belongs to a BGP neighbor");
      }
      if (!direction.equals("in") && !direction.equals("out")) {
        throw new IllegalArgumentException("a policy direction is in or out: " + direction);
      }
    }
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
      Set<RouterId> candidates =
          boundaryRouters(
              routerEvidence, evidence, predicate, specification.status().expectPresent());
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
    result.addAll(forbiddenAttachments(state));
    return List.copyOf(result);
  }

  /*
   * Finds existing policies that certainly reject an accepted route on the exact first BGP edge.
   *
   * <p>A forbidden accepted path can be stopped either on import at its source or on export at its
   * first hop. Address ownership identifies the corresponding neighbor object. Only a policy whose
   * typed first-match semantics rejects the destination is retained, and only when that direction
   * currently has no attachment.
   */
  private static List<ProtocolTransfer> forbiddenAttachments(RepairState state) {
    Map<AttachmentKey, LinkedHashSet<Specification>> targets = new LinkedHashMap<>();
    DataPlaneEvidence evidence = state.verification().evidence();
    for (Specification specification : state.verification().verdict().unresolved()) {
      for (DataPlaneEvidence.Trace trace :
          evidence.traces().getOrDefault(specification.predicate(), List.of())) {
        if (trace.disposition() != DataPlaneEvidence.Disposition.ACCEPTED
            || trace.hops().size() < 2) {
          continue;
        }
        boolean forbidden = !specification.status().expectPresent();
        if (specification.predicate() instanceof ForwardingPredicate.Waypointing waypointing) {
          forbidden =
              trace.hops().contains(waypointing.waypoint())
                  != specification.status().expectPresent();
        }
        if (!forbidden) {
          continue;
        }
        RouterId source = trace.hops().get(0);
        RouterId firstHop = trace.hops().get(1);
        addAttachments(
            state,
            source,
            firstHop,
            "in",
            specification,
            specification.predicate().destination(),
            targets);
        addAttachments(
            state,
            firstHop,
            source,
            "out",
            specification,
            specification.predicate().destination(),
            targets);
      }
    }
    List<ProtocolTransfer> result = new ArrayList<>();
    targets.forEach(
        (key, specifications) ->
            result.add(
                new ProtocolTransfer(
                    key.policy(),
                    List.copyOf(specifications),
                    List.of(),
                    Optional.of(key.attachment()))));
    return List.copyOf(result);
  }

  private static void addAttachments(
      RepairState state,
      RouterId local,
      RouterId remote,
      String direction,
      Specification specification,
      se.kth.sonar.network.Prefix destination,
      Map<AttachmentKey, LinkedHashSet<Specification>> targets) {
    ProgramObject.Ref remoteRef = new ProgramObject.Ref(ObjectType.ROUTER, remote, List.of());
    List<ProgramObject> policies =
        state.program().objectsOf(local).stream()
            .filter(object -> object.ref().type().equals("route-map"))
            .filter(
                policy ->
                    RouteMapEvaluation.definitelyDenies(state.program(), policy.ref(), destination))
            .toList();
    for (ProgramObject neighbor : state.program().objectsOf(local)) {
      if (!neighbor.ref().type().equals("bgp-neighbor")
          || neighbor.value("peer-router").filter(remoteRef.toString()::equals).isEmpty()
          || neighbor.has("route-map-" + direction)) {
        continue;
      }
      for (ProgramObject policy : policies) {
        AttachmentKey key =
            new AttachmentKey(policy.ref(), new Attachment(neighbor.ref(), direction));
        targets.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(specification);
      }
    }
  }

  private static Set<RouterId> boundaryRouters(
      RouterEvidence routerEvidence,
      DataPlaneEvidence evidence,
      ForwardingPredicate predicate,
      boolean expectPresent) {
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
            && (trace.hops().contains(waypointing.waypoint()) != expectPresent)) {
          trace.hops().stream().filter(holders::contains).forEach(candidates::add);
        }
      }
    }
    return Set.copyOf(candidates);
  }
}
