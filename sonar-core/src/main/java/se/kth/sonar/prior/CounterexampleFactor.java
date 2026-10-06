package se.kth.sonar.prior;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.MeshRegion;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.BgpSessionCut;
import se.kth.sonar.state.ForbiddenDelivery;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.state.SessionPolicy;
import se.kth.sonar.state.WaypointDetour;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Weights regions that span an exact relation named by a formal counterexample.
 *
 * <p>For an isolation violation, including a reachability predicate whose specification expects
 * absence, the first forwarding edge is a concrete forbidden relation. For a waypoint violation,
 * the source and required waypoint are operands of the obligation itself. A no-route trace that
 * stops at its source identifies its exact origin slice and the sessions crossing the observed
 * route-propagation boundary. Only when neither slice exists is the source's local route program
 * the residual explanation. A relation of the source's router object is where that program is known
 * to be incomplete, so it weighs with the reduction from every router to the sources left without
 * an explanation; the router object as a whole weighs without it. The origin slice includes the
 * interface or route object that owns the destination and, when an exact static route has no
 * matching BGP origin, the same-prefix BGP origin statements that can materialise the missing
 * relation. When nothing originates the destination exactly, the interfaces whose subnet holds it
 * or lies in it are where it would originate under another mask. A boundary session is identified
 * by joining its peer address to interface ownership, rather than by router proximity. For a
 * path-diversity deficit, an unused physical next hop that already holds the route identifies a
 * possible protocol-transfer boundary; typed route-map evaluation then retains only policies that
 * permit that destination. For a delivered path that bypasses its required waypoint, a BGP session
 * between the waypoint and a router on the path is where the path could have turned, and the
 * policies that set the attributes of the route along that session decide whether it does. For a
 * delivery that must not happen, the sessions between autonomous systems on its path are where the
 * route was let through, except those over which a satisfied obligation has the same destination
 * delivered, and attaching a rejecting policy to such a session weighs as the session does. When no
 * obligation requires a delivery to that destination from anywhere, what originates it weighs as
 * the origin of a missing route does. Each distinct violated predicate contributes one observation.
 * A matching region with {@code n} observations receives the Jeffreys odds {@code (n + 1/2) /
 * (1/2)}; unrelated regions retain weight one. This is deliberately narrower than topology
 * proximity: it uses only relations present in the specification or observed by the verifier.
 */
public final class CounterexampleFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private static final double HALF = 0.5;

  private final ContentCache<Evidence> observations = new ContentCache<>(CACHE_CAPACITY);

  private record Evidence(
      Map<Pair, Integer> relations,
      Map<RouterId, Integer> noRouteSources,
      Map<ProgramObject.Ref, Integer> noRouteOrigins,
      Map<ProgramObject.Ref, Integer> boundarySites,
      Map<ProgramObject.Ref, Integer> waypointSessions,
      Map<ProgramObject.Ref, Integer> detourPolicies,
      Map<ProgramObject.Ref, Integer> forbiddenPolicies,
      Map<ProgramObject.Ref, Integer> forbiddenOrigins,
      Map<ContentHash, Integer> sessionCuts,
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
    for (ProgramObject.Ref object : anchors(region)) {
      int count = evidence.noRouteSources().getOrDefault(object.router(), 0);
      if (object.type().equals(ObjectType.ROUTER) && count > 0) {
        long universe = Math.max(1, state.program().objects(ObjectType.ROUTER).size());
        double reduction =
            region instanceof RelationRegion
                ? (double) universe / evidence.noRouteSources().size()
                : 1;
        weight = Math.max(weight, reduction * (count + HALF) / HALF);
      }
    }
    if (region instanceof SiteRegion site) {
      int count = evidence.noRouteOrigins().getOrDefault(site.site(), 0);
      count = Math.max(count, evidence.boundarySites().getOrDefault(site.site(), 0));
      count = Math.max(count, evidence.forbiddenOrigins().getOrDefault(site.site(), 0));
      if (count > 0) {
        weight = Math.max(weight, (count + HALF) / HALF);
      }
      int sessions = evidence.waypointSessions().getOrDefault(site.site(), 0);
      if (sessions > 0) {
        long universe = state.program().objects("bgp-neighbor").size();
        double reduction = (double) universe / evidence.waypointSessions().size();
        weight = Math.max(weight, reduction * (sessions + HALF) / HALF);
      }
      for (Map<ProgramObject.Ref, Integer> policies :
          List.of(evidence.detourPolicies(), evidence.forbiddenPolicies())) {
        weight = Math.max(weight, crossingWeight(policies, site.site(), state));
      }
    }
    if (region instanceof RelationRegion relation) {
      int count = 0;
      for (ProgramObject.Ref role : relation.instance().roles().values()) {
        count = Math.max(count, evidence.noRouteOrigins().getOrDefault(role, 0));
        count = Math.max(count, evidence.boundarySites().getOrDefault(role, 0));
        count = Math.max(count, evidence.forbiddenOrigins().getOrDefault(role, 0));
      }
      if (count > 0) {
        // The reduction is from every interface to the origins of missing routes; a count that
        // comes only from boundary or forbidden origins has no such origins to reduce to.
        double reduction =
            relation.instance().relation().equals("address-identity")
                    && !evidence.noRouteOrigins().isEmpty()
                ? (double) state.program().objects("interface").size()
                    / evidence.noRouteOrigins().size()
                : 1;
        weight = Math.max(weight, reduction * (count + HALF) / HALF);
      }
      int cuts = evidence.sessionCuts().getOrDefault(relation.instance().id(), 0);
      if (cuts > 0) {
        long universe =
            state.program().instances("bgp-session").stream()
                .filter(RelationInstance::satisfied)
                .count();
        double reduction = (double) Math.max(1, universe) / evidence.sessionCuts().size();
        weight = Math.max(weight, reduction * (cuts + HALF) / HALF);
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
      if (transfer.transfer().attachment().isPresent()) {
        weight =
            Math.max(
                weight,
                crossingWeight(
                    evidence.forbiddenPolicies(),
                    transfer.transfer().attachment().get().neighbor(),
                    state));
      }
    }
    int missingSessionObservations = missingSessionObservations(region, evidence.relations());
    if (missingSessionObservations > 0) {
      long universe = Math.max(1, state.program().objects("bgp-neighbor").size());
      double reduction = (double) universe / evidence.relations().size();
      weight = Math.max(weight, reduction * (missingSessionObservations + HALF) / HALF);
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

  /** Returns the objects a site or relation region is about: its site, or its roles. */
  private static Set<ProgramObject.Ref> anchors(Region region) {
    return switch (region) {
      case SiteRegion site -> Set.of(site.site());
      case RelationRegion relation -> Set.copyOf(relation.instance().roles().values());
      default -> Set.of();
    };
  }

  /**
   * Returns the weight {@code object} has as part of the session policy slices {@code policies}:
   * the Jeffreys odds of its observations times the reduction from every session policy object to
   * the slices, or one if it is in none.
   */
  private static double crossingWeight(
      Map<ProgramObject.Ref, Integer> policies, ProgramObject.Ref object, RepairState state) {
    int crossings = policies.getOrDefault(object, 0);
    if (crossings == 0) {
      return 1;
    }
    long universe =
        SessionPolicy.TYPES.stream().mapToLong(type -> state.program().objects(type).size()).sum();
    return (double) universe / policies.size() * (crossings + HALF) / HALF;
  }

  /**
   * Returns observations whose exact formal endpoints are completed by a missing-session region.
   *
   * <p>Existing BGP neighbor objects are handled above. Incidence and mesh regions are the finite
   * representation of the complementary case: the session object itself is absent, so there is no
   * site to join against. Matching their typed endpoints to the waypoint operands gives the same
   * finite-hypothesis reduction without relying on router names or topology proximity.
   */
  private static int missingSessionObservations(Region region, Map<Pair, Integer> relations) {
    int observations = 0;
    if (region instanceof IncidenceRegion incidence) {
      RouterId reflector = incidence.incidence().reflector().router();
      for (ProgramObject.Ref client : incidence.incidence().clients()) {
        observations += relations.getOrDefault(Pair.of(reflector, client.router()), 0);
      }
    } else if (region instanceof MeshRegion mesh) {
      RouterId member = mesh.mesh().member().router();
      for (ProgramObject.Ref peer : mesh.mesh().peers()) {
        observations += relations.getOrDefault(Pair.of(member, peer.router()), 0);
      }
    }
    return observations;
  }

  private static Evidence observations(RepairState state) {
    Map<Pair, Integer> relations = new HashMap<>();
    Map<RouterId, Integer> noRouteSources = new HashMap<>();
    Map<ProgramObject.Ref, Integer> noRouteOrigins = new HashMap<>();
    Map<ProgramObject.Ref, Integer> boundarySites = new HashMap<>();
    Map<ProgramObject.Ref, Integer> waypointSessions = new HashMap<>();
    Map<ContentHash, Integer> sessionCuts = new HashMap<>();
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
      if (routeHolderDeadEndAtSource(evidence, predicate)) {
        for (ProgramObject.Ref site : exportingPolicySlice(state.program(), predicate.source())) {
          boundarySites.merge(site, 1, Integer::sum);
        }
      }
      if (RouterEvidence.noRouteAtSource(evidence, predicate)) {
        Set<ProgramObject.Ref> exactOrigins = origins(state.program(), predicate.destination());
        for (ProgramObject.Ref origin : exactOrigins) {
          noRouteOrigins.merge(origin, 1, Integer::sum);
        }
        Set<RouterEvidence.Link> frontiers = Set.of();
        if (RouterEvidence.frontierApplies(specification)) {
          frontiers = routerEvidence.routeFrontier(predicate.source(), predicate.destination());
          for (RouterEvidence.Link frontier : frontiers) {
            for (ProgramObject.Ref site : boundarySites(state.program(), frontier)) {
              boundarySites.merge(site, 1, Integer::sum);
            }
          }
        }
        if (exactOrigins.isEmpty() && frontiers.isEmpty()) {
          noRouteSources.merge(predicate.source(), 1, Integer::sum);
        }
      }
      if (predicate instanceof ForwardingPredicate.Waypointing waypointing) {
        boolean observed =
            evidence.traces().getOrDefault(predicate, List.of()).stream()
                .anyMatch(trace -> trace.hops().contains(waypointing.waypoint()));
        if (specification.status().expectPresent()
            && !observed
            && !waypointing.source().equals(waypointing.waypoint())) {
          relations.merge(Pair.of(waypointing.source(), waypointing.waypoint()), 1, Integer::sum);
          for (ProgramObject.Ref session :
              sessionSites(state.program(), waypointing.source(), waypointing.waypoint())) {
            waypointSessions.merge(session, 1, Integer::sum);
          }
          for (ProgramObject.Ref origin : hostOriginsAtWaypoint(state.program(), waypointing)) {
            noRouteOrigins.merge(origin, 1, Integer::sum);
          }
        } else if (!specification.status().expectPresent() && observed) {
          addForbiddenWaypointChoices(state, specification, boundarySites);
        }
      } else if (specification.forbidsDelivery()) {
        for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
          if (trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED
              && trace.hops().size() >= 2) {
            Pair edge = Pair.of(trace.hops().get(0), trace.hops().get(1));
            relations.merge(edge, 1, Integer::sum);
            for (ProgramObject.Ref site :
                boundarySites(
                    state.program(),
                    new RouterEvidence.Link(trace.hops().get(0), trace.hops().get(1)))) {
              boundarySites.merge(site, 1, Integer::sum);
            }
          }
        }
      }
      String transferKey = predicate.source() + "|" + predicate.destination();
      if (seenTransfers.add(transferKey)) {
        countTransfers(state, routerEvidence, specification, transfers);
      }
    }
    for (BgpSessionCut cut : BgpSessionCut.infer(state)) {
      int count =
          (int) cut.targets().stream().map(target -> target.predicate().key()).distinct().count();
      sessionCuts.put(cut.session().id(), count);
    }
    return new Evidence(
        Map.copyOf(relations),
        Map.copyOf(noRouteSources),
        Map.copyOf(noRouteOrigins),
        Map.copyOf(boundarySites),
        Map.copyOf(waypointSessions),
        WaypointDetour.policies(state),
        ForbiddenDelivery.policies(state),
        ForbiddenDelivery.origins(state),
        Map.copyOf(sessionCuts),
        Map.copyOf(transfers));
  }

  /**
   * Returns host-address interfaces at a required waypoint for an unresolved host destination. The
   * formal waypoint operand supplies the router; no naming or topology-distance convention is
   * needed. A host route can be originated only by a host-address object on that router. A local
   * identity equal to the destination is direct evidence; a duplicated local host address is also
   * evidence because the program violates unique address ownership and therefore cannot name both
   * formal endpoints.
   */
  private static Set<ProgramObject.Ref> hostOriginsAtWaypoint(
      ProgramModel program, ForwardingPredicate.Waypointing waypointing) {
    if (waypointing.destination().length() != 32) {
      return Set.of();
    }
    ProgramObject owner =
        program
            .object(new ProgramObject.Ref(ObjectType.ROUTER, waypointing.waypoint(), List.of()))
            .orElse(null);
    String address = waypointing.destination().network().toString();
    if (owner == null) {
      return Set.of();
    }
    boolean declared =
        owner.values("ospf-router-id").contains(address)
            || owner.values("bgp-router-id").contains(address);
    Set<ProgramObject.Ref> origins = new HashSet<>();
    for (ProgramObject object : program.objectsOf(waypointing.waypoint())) {
      if (!object.ref().type().equals("interface")) {
        continue;
      }
      boolean hostAddress =
          object.values("subnet").stream()
              .map(Prefix::tryParse)
              .flatMap(Optional::stream)
              .anyMatch(prefix -> prefix.length() == 32);
      boolean duplicated =
          object.values("address").stream()
              .anyMatch(
                  candidate ->
                      program.objects("interface").stream()
                              .filter(peer -> peer.values("address").contains(candidate))
                              .map(ProgramObject::ref)
                              .distinct()
                              .count()
                          > 1);
      if (hostAddress && (declared || duplicated)) {
        origins.add(object.ref());
      }
    }
    return Set.copyOf(origins);
  }

  /** Returns whether a BGP route exists at the source but cannot produce a forwarding next hop. */
  private static boolean routeHolderDeadEndAtSource(
      DataPlaneEvidence evidence, ForwardingPredicate predicate) {
    if (!evidence
        .routeHolders()
        .getOrDefault(predicate.destination(), Set.of())
        .contains(predicate.source())) {
      return false;
    }
    List<DataPlaneEvidence.Trace> traces = evidence.traces().get(predicate);
    return traces != null
        && !traces.isEmpty()
        && traces.stream()
            .allMatch(
                trace ->
                    trace.hops().equals(List.of(predicate.source()))
                        && (trace.disposition() == DataPlaneEvidence.Disposition.NO_ROUTE
                            || trace.disposition() == DataPlaneEvidence.Disposition.NULL_ROUTED));
  }

  /**
   * Returns the remote session and outbound policy objects that can have supplied a source's BGP
   * route. When the source holds a route but its forwarding trace is still null routed, this is the
   * exact control-plane slice capable of making the selected next hop unusable.
   */
  private static Set<ProgramObject.Ref> exportingPolicySlice(
      ProgramModel program, RouterId source) {
    Map<String, RouterId> addresses = new HashMap<>();
    for (ProgramObject object : program.objects("interface")) {
      object.value("address").ifPresent(address -> addresses.put(address, object.ref().router()));
    }
    Set<ProgramObject.Ref> sites = new HashSet<>();
    for (ProgramObject neighbor : program.objects("bgp-neighbor")) {
      Optional<RouterId> peer =
          neighbor
              .value("peer-router")
              .filter(value -> ProgramObject.Ref.isReference(value, ObjectType.ROUTER))
              .map(ProgramObject.Ref::parse)
              .map(ProgramObject.Ref::router);
      if (peer.isEmpty() && !neighbor.ref().key().isEmpty()) {
        peer = Optional.ofNullable(addresses.get(neighbor.ref().key().getFirst()));
      }
      if (peer.isEmpty() || !peer.get().equals(source)) {
        continue;
      }
      sites.add(neighbor.ref());
      for (String value : neighbor.values("route-map-out")) {
        ProgramObject.Ref map =
            ProgramObject.Ref.isReference(value, "route-map")
                ? ProgramObject.Ref.parse(value)
                : new ProgramObject.Ref("route-map", neighbor.ref().router(), List.of(value));
        sites.add(map);
        program.objectsOf(neighbor.ref().router()).stream()
            .filter(object -> object.ref().type().equals("route-map-entry"))
            .filter(object -> !object.ref().key().isEmpty())
            .filter(object -> object.ref().key().getFirst().equals(map.key().getFirst()))
            .map(ProgramObject::ref)
            .forEach(sites::add);
      }
    }
    return Set.copyOf(sites);
  }

  /**
   * Returns BGP neighbors whose peer address joins the two ends of an observed boundary, with the
   * route-map objects that control them.
   */
  static Set<ProgramObject.Ref> boundarySites(ProgramModel program, RouterEvidence.Link boundary) {
    Map<String, RouterId> addresses = new HashMap<>();
    for (ProgramObject object : program.objects("interface")) {
      object.value("address").ifPresent(address -> addresses.put(address, object.ref().router()));
    }
    Set<ProgramObject.Ref> sites = new HashSet<>();
    for (ProgramObject object : program.objects("bgp-neighbor")) {
      if (object.ref().key().isEmpty()) {
        continue;
      }
      RouterId peer = addresses.get(object.ref().key().getFirst());
      if (peer != null
          && Pair.of(object.ref().router(), peer)
              .equals(Pair.of(boundary.first(), boundary.second()))) {
        addPolicySlice(program, object, sites);
      }
    }
    return Set.copyOf(sites);
  }

  /** Adds one BGP session and the route-map objects that directly control that session. */
  private static void addPolicySlice(
      ProgramModel program, ProgramObject neighbor, Set<ProgramObject.Ref> sites) {
    sites.add(neighbor.ref());
    for (String attribute : List.of("route-map-in", "route-map-out")) {
      for (String value : neighbor.values(attribute)) {
        ProgramObject.Ref map =
            ProgramObject.Ref.isReference(value, "route-map")
                ? ProgramObject.Ref.parse(value)
                : new ProgramObject.Ref("route-map", neighbor.ref().router(), List.of(value));
        if (program.object(map).isEmpty()) {
          continue;
        }
        sites.add(map);
        program.objectsOf(map.router()).stream()
            .filter(object -> object.ref().type().equals("route-map-entry"))
            .filter(object -> !object.ref().key().isEmpty())
            .filter(object -> object.ref().key().getFirst().equals(map.key().getFirst()))
            .map(ProgramObject::ref)
            .forEach(sites::add);
      }
    }
  }

  /** Adds the policy choices at the source of a path through a forbidden waypoint. */
  private static void addForbiddenWaypointChoices(
      RepairState state, Specification specification, Map<ProgramObject.Ref, Integer> sites) {
    ForwardingPredicate.Waypointing waypointing =
        (ForwardingPredicate.Waypointing) specification.predicate();
    Set<RouterId> holders =
        state
            .verification()
            .evidence()
            .routeHolders()
            .getOrDefault(waypointing.destination(), Set.of());
    Set<RouterEvidence.Link> choices = new HashSet<>();
    for (DataPlaneEvidence.Trace trace :
        state.verification().evidence().traces().getOrDefault(waypointing, List.of())) {
      if (trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED
          && trace.hops().contains(waypointing.waypoint())
          && trace.hops().size() >= 2) {
        choices.add(RouterEvidence.Link.of(trace.hops().get(0), trace.hops().get(1)));
      }
    }
    for (RouterId peer : state.problem().topology().neighbours(waypointing.source())) {
      if (holders.contains(peer)) {
        choices.add(RouterEvidence.Link.of(waypointing.source(), peer));
      }
    }
    for (RouterEvidence.Link choice : choices) {
      for (ProgramObject.Ref site : boundarySites(state.program(), choice)) {
        sites.merge(site, 1, Integer::sum);
      }
    }
  }

  /** Returns the exact BGP session objects joining two formal waypoint operands. */
  private static Set<ProgramObject.Ref> sessionSites(
      ProgramModel program, RouterId first, RouterId second) {
    Set<ProgramObject.Ref> sites = new HashSet<>();
    for (ProgramObject object : program.objects("bgp-neighbor")) {
      Optional<RouterId> peer =
          object
              .value("peer-router")
              .filter(value -> ProgramObject.Ref.isReference(value, ObjectType.ROUTER))
              .map(ProgramObject.Ref::parse)
              .map(ProgramObject.Ref::router);
      if (peer.isPresent()
          && ((object.ref().router().equals(first) && peer.get().equals(second))
              || (object.ref().router().equals(second) && peer.get().equals(first)))) {
        sites.add(object.ref());
      }
    }
    return Set.copyOf(sites);
  }

  /** Returns the exact origin objects and edit sites that can complete a missing BGP origin. */
  private static Set<ProgramObject.Ref> origins(ProgramModel program, Prefix destination) {
    Set<ProgramObject.Ref> origins = new HashSet<>();
    Set<RouterId> missingBgpOrigins = new HashSet<>();
    for (ProgramObject object : program.objects()) {
      List<String> values = new ArrayList<>();
      switch (object.ref().type()) {
        case "interface" -> {
          values.addAll(object.values("subnet"));
          object.value("address").ifPresent(address -> values.add(address + "/32"));
        }
        case "bgp-network", "static-route" -> values.addAll(object.values("prefix"));
        default -> {}
      }
      if (values.stream()
          .map(Prefix::tryParse)
          .flatMap(Optional::stream)
          .anyMatch(destination::equals)) {
        origins.add(object.ref());
        if (object.ref().type().equals("static-route")) {
          missingBgpOrigins.add(object.ref().router());
        }
      }
    }
    if (origins.isEmpty() && destination.length() > 0) {
      for (ProgramObject object : program.objects("interface")) {
        if (object.values("subnet").stream()
            .map(Prefix::tryParse)
            .flatMap(Optional::stream)
            .anyMatch(subnet -> subnet.contains(destination) || destination.contains(subnet))) {
          origins.add(object.ref());
        }
      }
    }
    for (ProgramObject object : program.objects("bgp-network")) {
      if (missingBgpOrigins.contains(object.ref().router())
          && object.values("prefix").stream()
              .map(Prefix::tryParse)
              .flatMap(Optional::stream)
              .anyMatch(prefix -> prefix.length() == destination.length())) {
        origins.add(object.ref());
      }
    }
    return Set.copyOf(origins);
  }

  /** Joins one forwarding observation to exact BGP-to-OSPF policy uses. */
  private static void countTransfers(
      RepairState state,
      RouterEvidence routerEvidence,
      Specification specification,
      Map<ProgramObject.Ref, Integer> transfers) {
    ForwardingPredicate predicate = specification.predicate();
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<RouterId> holders = evidence.routeHolders().getOrDefault(predicate.destination(), Set.of());
    Set<RouterId> candidates = new HashSet<>();
    if (RouterEvidence.noRouteAtSource(evidence, predicate)) {
      candidates.addAll(
          routerEvidence.transferBoundary(predicate.source(), predicate.destination()));
    }
    if (predicate instanceof ForwardingPredicate.Waypointing waypointing) {
      for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
        if (trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED
            && (trace.hops().contains(waypointing.waypoint())
                != specification.status().expectPresent())) {
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
