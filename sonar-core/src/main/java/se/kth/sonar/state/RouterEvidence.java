package se.kth.sonar.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Which routers the verifier's evidence implicates for each specification: the source, the
 * waypoint, every hop of the traced flows, the boundary of a no-route component, and the routers
 * that originate the destination prefix through an interface, a network statement or a static
 * route.
 */
public final class RouterEvidence {
  private final RepairState state;
  private final Map<Prefix, Set<ProgramObject.Ref>> origins;

  private RouterEvidence(RepairState state) {
    this.state = state;
    this.origins = origins(state.program());
  }

  /** Reads the evidence of {@code state}. */
  public static RouterEvidence of(RepairState state) {
    return new RouterEvidence(Objects.requireNonNull(state, "state"));
  }

  /** Returns the routers that originate {@code prefix}. */
  public Set<RouterId> owners(Prefix prefix) {
    Set<RouterId> owners = new LinkedHashSet<>();
    origins(prefix).forEach(origin -> owners.add(origin.router()));
    return owners;
  }

  /**
   * Returns the objects that originate {@code prefix}: the interfaces on it, and the network
   * statements and static routes for it.
   */
  public Set<ProgramObject.Ref> origins(Prefix prefix) {
    return origins.getOrDefault(prefix, Set.of());
  }

  /**
   * Returns the BGP neighbor objects by which {@code first} and {@code second} peer with each
   * other, if they run BGP in different autonomous systems.
   */
  public static Set<ProgramObject.Ref> externalSessions(
      ProgramModel program, RouterId first, RouterId second) {
    Set<ProgramObject.Ref> sessions = new LinkedHashSet<>();
    if (BgpPeering.external(program, first, second)) {
      BgpPeering.sessions(program, first, second).forEach(session -> sessions.add(session.ref()));
      BgpPeering.sessions(program, second, first).forEach(session -> sessions.add(session.ref()));
    }
    return sessions;
  }

  /** An unordered physical router relation. */
  public record Link(RouterId first, RouterId second) implements Comparable<Link> {
    /** Returns the link in canonical router order. */
    public static Link of(RouterId left, RouterId right) {
      return left.compareTo(right) <= 0 ? new Link(left, right) : new Link(right, left);
    }

    @Override
    public int compareTo(Link other) {
      int firstOrder = first.compareTo(other.first);
      return firstOrder != 0 ? firstOrder : second.compareTo(other.second);
    }
  }

  /**
   * Returns the exact physical boundary where the no-route component around {@code source} meets
   * routers that originate or forward {@code destination}.
   */
  public Set<Link> routeFrontier(RouterId source, Prefix destination) {
    Set<RouterId> knowing = new HashSet<>(owners(destination));
    knowing.addAll(
        state.verification().evidence().forwarders().getOrDefault(destination, Set.of()));
    return routeFrontier(state.problem().topology(), source, knowing);
  }

  /**
   * Returns the BGP holders of {@code destination} at which its route could enter the IGP that
   * leaves {@code source} without it: the holders on the nearest boundary of the no-route component
   * around the source. A holder that peers internally with another holder and externally with none
   * can only have the route from inside its autonomous system; IOS does not redistribute such a
   * route into an IGP, so the boundary is sought beyond it.
   */
  public Set<RouterId> transferBoundary(RouterId source, Prefix destination) {
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<RouterId> holders = evidence.routeHolders().getOrDefault(destination, Set.of());
    Set<RouterId> suppliers = new HashSet<>(holders);
    suppliers.addAll(owners(destination));
    Set<RouterId> internal = new HashSet<>();
    for (RouterId holder : holders) {
      if (owners(destination).contains(holder)) {
        continue;
      }
      boolean inside = false;
      boolean outside = false;
      for (RouterId peer : BgpPeering.peers(state.program(), holder)) {
        if (suppliers.contains(peer)) {
          boolean external = BgpPeering.external(state.program(), holder, peer);
          outside |= external;
          inside |= !external;
        }
      }
      if (inside && !outside) {
        internal.add(holder);
      }
    }
    Set<RouterId> knowing = new HashSet<>(owners(destination));
    knowing.addAll(evidence.forwarders().getOrDefault(destination, Set.of()));
    knowing.removeAll(internal);
    Set<RouterId> boundary = new LinkedHashSet<>();
    for (Link link : routeFrontier(state.problem().topology(), source, knowing)) {
      for (RouterId end : List.of(link.first(), link.second())) {
        if (holders.contains(end) && !internal.contains(end)) {
          boundary.add(end);
        }
      }
    }
    return boundary;
  }

  /** Returns the nearest physical boundary from {@code source} to any router in {@code knowing}. */
  public static Set<Link> routeFrontier(Topology topology, RouterId source, Set<RouterId> knowing) {
    Set<Link> frontier = new LinkedHashSet<>();
    if (knowing.contains(source)) {
      return frontier;
    }
    record Hop(RouterId router, int distance) {}
    Set<RouterId> seen = new HashSet<>(Set.of(source));
    Deque<Hop> queue = new ArrayDeque<>(List.of(new Hop(source, 0)));
    int nearest = Integer.MAX_VALUE;
    while (!queue.isEmpty()) {
      Hop hop = queue.removeFirst();
      if (hop.distance() > nearest) {
        break;
      }
      RouterId router = hop.router();
      List<RouterId> unknown = new ArrayList<>();
      for (RouterId peer : topology.neighbours(router)) {
        if (knowing.contains(peer)) {
          frontier.add(Link.of(router, peer));
          nearest = hop.distance();
        } else if (!seen.contains(peer)) {
          unknown.add(peer);
        }
      }
      if (hop.distance() < nearest) {
        for (RouterId peer : unknown) {
          if (seen.add(peer)) {
            queue.addLast(new Hop(peer, hop.distance() + 1));
          }
        }
      }
    }
    return Set.copyOf(frontier);
  }

  /** Returns whether every observed flow stops at its source because no route exists. */
  public static boolean noRouteAtSource(DataPlaneEvidence evidence, ForwardingPredicate predicate) {
    List<DataPlaneEvidence.Trace> traces = evidence.traces().get(predicate);
    if (traces == null || traces.isEmpty()) {
      return false;
    }
    return traces.stream()
        .allMatch(
            trace ->
                trace.disposition() == DataPlaneEvidence.Disposition.NO_ROUTE
                    && trace.hops().equals(List.of(predicate.source())));
  }

  /**
   * Returns whether a no-route observation can implicate a route-propagation frontier.
   *
   * <p>When isolation itself is required, a no-route trace contains no forwarding boundary; only
   * its source and exact origin program are evidence. An obligation for the opposite outcome, or
   * any path-bearing predicate, can use the frontier.
   */
  public static boolean frontierApplies(Specification specification) {
    return !(specification.predicate() instanceof ForwardingPredicate.Isolation)
        || !specification.status().expectPresent();
  }

  /** Returns the routers implicated for {@code specification}, in evidence order. */
  public Set<RouterId> routers(Specification specification) {
    Set<RouterId> routers = new LinkedHashSet<>();
    ForwardingPredicate predicate = specification.predicate();
    routers.add(predicate.source());
    if (predicate instanceof ForwardingPredicate.Waypointing waypointing) {
      routers.add(waypointing.waypoint());
    }
    DataPlaneEvidence evidence = state.verification().evidence();
    for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
      routers.addAll(trace.hops());
    }
    if (frontierApplies(specification) && noRouteAtSource(evidence, predicate)) {
      for (Link link : routeFrontier(predicate.source(), predicate.destination())) {
        routers.add(link.first());
        routers.add(link.second());
      }
    }
    routers.addAll(owners(predicate.destination()));
    routers.removeIf(router -> !state.snapshot().contains(router));
    return routers;
  }

  /** Returns the unresolved specifications that implicate each router, in specification order. */
  public Map<RouterId, List<Specification>> unresolvedByRouter() {
    Map<RouterId, List<Specification>> implicated = new LinkedHashMap<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      for (RouterId router : routers(specification)) {
        implicated.computeIfAbsent(router, r -> new ArrayList<>()).add(specification);
      }
    }
    return implicated;
  }

  private static Map<Prefix, Set<ProgramObject.Ref>> origins(ProgramModel program) {
    Map<Prefix, Set<ProgramObject.Ref>> origins = new HashMap<>();
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
      for (String value : values) {
        Prefix.tryParse(value)
            .ifPresent(
                prefix ->
                    origins.computeIfAbsent(prefix, p -> new LinkedHashSet<>()).add(object.ref()));
      }
    }
    return origins;
  }
}
