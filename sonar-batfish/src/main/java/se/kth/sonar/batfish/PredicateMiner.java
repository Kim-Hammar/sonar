package se.kth.sonar.batfish;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.batfish.datamodel.Ip;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Mines forwarding predicates from the rows of the forwarding behaviour question, with the
 * benchmark's meaning of reachability, isolation, waypointing and load balancing.
 *
 * <p>For each exact route prefix, every {@code forwarded} row of a router is an edge to the routers
 * that own its next-hop address, an {@code accepted} row is an edge to the prefix's sink, and every
 * other row is a dead end. A router with a null-routed or filter-blocked row loses all its
 * forwarded edges for that prefix. A router reaches the prefix when a path of these edges leads to
 * the sink; it is isolated when it is in the graph but no path does; a waypoint is a router that
 * every path passes through; and traffic is load balanced when the router forwards to two or more
 * routers. Routers that no row mentions for a prefix hold no predicate for it.
 */
final class PredicateMiner {
  private static final String SINK = "prefix:";
  private static final String TERMINAL = "terminal:";
  private static final String BLOCKED_INCOMING = "forwarded_blocked_incoming_filter";
  private static final String INTERFACE = "interface:";
  private static final Set<String> BLOCKING =
      Set.of("denied", "denied_in", "denied_out", "drop", "dropped", "null_routed");
  private static final int MAX_TRACES = 4;
  private static final int MAX_HOPS = 64;

  private PredicateMiner() {}

  /**
   * Returns which specified predicates hold and the evidence for every specification, given the
   * rows, the routers owning each address, and the routers holding a BGP route to each prefix.
   */
  static DataPlaneEngine.Observation observe(
      List<ForwardingRow> rows,
      Map<Ip, Set<String>> owners,
      Map<Prefix, Set<RouterId>> routeHolders,
      SpecificationSet specifications) {
    Map<Prefix, Graph> graphs = graphs(rows, owners);
    Set<ForwardingPredicate> holding = new HashSet<>();
    Map<ForwardingPredicate, List<DataPlaneEvidence.Trace>> traces = new LinkedHashMap<>();
    Map<Prefix, Set<RouterId>> holders = new HashMap<>();
    Map<Prefix, Set<RouterId>> forwarders = new HashMap<>();
    for (Specification specification : specifications.all()) {
      ForwardingPredicate predicate = specification.predicate();
      Graph graph = graphs.get(predicate.destination());
      if (graph != null && graph.holds(predicate)) {
        holding.add(predicate);
      }
      traces.put(predicate, graph == null ? noRoute(predicate) : graph.traces(predicate.source()));
      Set<RouterId> holding1 = routeHolders.get(predicate.destination());
      if (holding1 != null) {
        holders.putIfAbsent(predicate.destination(), holding1);
      }
      if (graph != null) {
        forwarders.computeIfAbsent(predicate.destination(), p -> graph.forwarders());
      }
    }
    return new DataPlaneEngine.Observation(
        holding, new DataPlaneEvidence(traces, holders, forwarders));
  }

  private static List<DataPlaneEvidence.Trace> noRoute(ForwardingPredicate predicate) {
    return List.of(
        new DataPlaneEvidence.Trace(
            List.of(predicate.source()), DataPlaneEvidence.Disposition.NO_ROUTE));
  }

  private static Map<Prefix, Graph> graphs(List<ForwardingRow> rows, Map<Ip, Set<String>> owners) {
    Map<Prefix, List<ForwardingRow>> byPrefix = new LinkedHashMap<>();
    for (ForwardingRow row : rows) {
      byPrefix
          .computeIfAbsent(Prefix.parse(row.prefix().toString()), p -> new ArrayList<>())
          .add(row);
    }
    Map<Prefix, Graph> graphs = new HashMap<>();
    byPrefix.forEach(
        (prefix, prefixRows) -> graphs.put(prefix, new Graph(prefix, prefixRows, owners)));
    return graphs;
  }

  private record Edge(String source, String target, String action) {}

  /** The forwarding graph of one prefix. */
  static final class Graph {
    private final String sink;
    private final Map<String, List<Edge>> edges = new LinkedHashMap<>();
    private final Set<String> devices = new TreeSet<>();
    private final Map<String, Set<String>> forward = new HashMap<>();
    private final Map<String, Set<String>> backward = new HashMap<>();
    private final Map<String, Set<String>> denials = new HashMap<>();
    private Set<String> reaching;
    private Map<String, String> dominators;

    Graph(Prefix prefix, List<ForwardingRow> rows, Map<Ip, Set<String>> owners) {
      this.sink = SINK + prefix;
      Set<Edge> seen = new LinkedHashSet<>();
      Set<String> blocked = new HashSet<>();
      for (ForwardingRow row : rows) {
        String action = row.action().toLowerCase(Locale.ROOT);
        if (action.equals(ForwardingRow.FORWARDED)) {
          for (String target : targets(row, owners)) {
            seen.add(new Edge(row.node(), target, action));
          }
        } else {
          String target = action.equals(ForwardingRow.ACCEPTED) ? sink : TERMINAL + action;
          seen.add(new Edge(row.node(), target, action));
          if (action.equals(BLOCKED_INCOMING)) {
            denials.computeIfAbsent(row.node(), n -> new LinkedHashSet<>()).addAll(row.receivers());
          }
          if (BLOCKING.contains(action) || action.startsWith("forwarded_blocked")) {
            blocked.add(row.node());
          }
        }
      }
      for (Edge edge : seen) {
        if (edge.action().equals(ForwardingRow.FORWARDED) && blocked.contains(edge.source())) {
          continue;
        }
        edges.computeIfAbsent(edge.source(), s -> new ArrayList<>()).add(edge);
        boolean sourceDevice = isDevice(edge.source());
        boolean targetDevice = isDevice(edge.target()) || edge.target().equals(sink);
        if (sourceDevice && targetDevice) {
          devices.add(edge.source());
          if (!edge.target().equals(sink)) {
            devices.add(edge.target());
          }
          forward.computeIfAbsent(edge.source(), s -> new LinkedHashSet<>()).add(edge.target());
          backward.computeIfAbsent(edge.target(), t -> new LinkedHashSet<>()).add(edge.source());
        }
      }
    }

    private static List<String> targets(ForwardingRow row, Map<Ip, Set<String>> owners) {
      Set<String> targets = new LinkedHashSet<>();
      if (row.nextHopInterface().isPresent()) {
        row.nextHopIp().map(owners::get).ifPresent(targets::addAll);
        if (targets.isEmpty()) {
          targets.add(INTERFACE + row.node() + "[" + row.nextHopInterface().get() + "]");
        }
      } else if (row.nextHopIp().isPresent()) {
        Set<String> owning = owners.get(row.nextHopIp().get());
        if (owning != null) {
          targets.addAll(owning);
        }
        if (targets.isEmpty()) {
          targets.add("next-hop:" + row.nextHopIp().get());
        }
      } else {
        targets.add("unknown-next-hop");
      }
      return new ArrayList<>(targets);
    }

    private static boolean isDevice(String name) {
      return !(name.startsWith(SINK)
          || name.startsWith(TERMINAL)
          || name.startsWith(INTERFACE)
          || name.startsWith("next-hop:")
          || name.startsWith("unknown-"));
    }

    /** Returns the devices with a path to the sink. */
    private Set<String> reaching() {
      if (reaching == null) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(sink));
        Set<String> visited = new HashSet<>(List.of(sink));
        while (!queue.isEmpty()) {
          String node = queue.removeFirst();
          for (String previous : backward.getOrDefault(node, Set.of())) {
            if (visited.add(previous)) {
              found.add(previous);
              queue.addLast(previous);
            }
          }
        }
        reaching = found;
      }
      return reaching;
    }

    /**
     * Returns the immediate dominator of every reaching device on the way to the sink: the device
     * that every path from it to the sink passes through last, or the sink itself.
     */
    private Map<String, String> dominators() {
      if (dominators != null) {
        return dominators;
      }
      List<String> order = new ArrayList<>();
      Set<String> visited = new HashSet<>();
      postorder(sink, visited, order);
      List<String> reversePostorder = order.reversed();
      Map<String, Integer> position = new HashMap<>();
      for (int at = 0; at < reversePostorder.size(); at++) {
        position.put(reversePostorder.get(at), at);
      }
      Map<String, String> idom = new HashMap<>();
      idom.put(sink, sink);
      boolean changed = true;
      while (changed) {
        changed = false;
        for (String node : reversePostorder) {
          if (node.equals(sink)) {
            continue;
          }
          String candidate = null;
          for (String predecessor : forward.getOrDefault(node, Set.of())) {
            if (!idom.containsKey(predecessor)) {
              continue;
            }
            candidate =
                candidate == null ? predecessor : intersect(candidate, predecessor, idom, position);
          }
          if (candidate != null && !candidate.equals(idom.get(node))) {
            idom.put(node, candidate);
            changed = true;
          }
        }
      }
      dominators = idom;
      return idom;
    }

    private void postorder(String node, Set<String> visited, List<String> order) {
      if (!visited.add(node)) {
        return;
      }
      for (String previous : backward.getOrDefault(node, Set.of())) {
        postorder(previous, visited, order);
      }
      order.add(node);
    }

    private static String intersect(
        String a, String b, Map<String, String> idom, Map<String, Integer> position) {
      String left = a;
      String right = b;
      while (!left.equals(right)) {
        while (position.get(left) > position.get(right)) {
          left = idom.get(left);
        }
        while (position.get(right) > position.get(left)) {
          right = idom.get(right);
        }
      }
      return left;
    }

    boolean holds(ForwardingPredicate predicate) {
      String source = predicate.source().name();
      return switch (predicate) {
        case ForwardingPredicate.Reachability _ -> reaching().contains(source);
        case ForwardingPredicate.Isolation _ ->
            devices.contains(source) && !reaching().contains(source);
        case ForwardingPredicate.Waypointing waypointing -> {
          String waypoint = waypointing.waypoint().name();
          if (!reaching().contains(source)
              || !reaching().contains(waypoint)
              || source.equals(waypoint)) {
            yield false;
          }
          Map<String, String> idom = dominators();
          String current = idom.get(source);
          while (current != null && !current.equals(sink)) {
            if (current.equals(waypoint)) {
              yield true;
            }
            current = idom.get(current);
          }
          yield false;
        }
        case ForwardingPredicate.LoadBalancing balancing ->
            reaching().contains(source) && nextHops(source) == balancing.routes();
      };
    }

    /** Returns the number of distinct routers that {@code node} forwards the prefix to. */
    private int nextHops(String node) {
      Set<String> targets = new HashSet<>();
      for (Edge edge : edges.getOrDefault(node, List.of())) {
        if (edge.action().equals(ForwardingRow.FORWARDED) && isDevice(edge.target())) {
          targets.add(edge.target());
        }
      }
      return targets.size();
    }

    /** Returns the routers that have a forwarding entry for the prefix. */
    Set<RouterId> forwarders() {
      Set<RouterId> result = new TreeSet<>();
      for (String node : edges.keySet()) {
        if (isDevice(node)) {
          result.add(RouterId.of(node));
        }
      }
      return Set.copyOf(result);
    }

    /**
     * Returns up to a few forwarding paths of traffic from {@code source} and how each ends; a flow
     * denied by the next hop's inbound filter ends at that next hop.
     */
    List<DataPlaneEvidence.Trace> traces(RouterId source) {
      List<DataPlaneEvidence.Trace> result = new ArrayList<>();
      if (!edges.containsKey(source.name())) {
        return List.of(
            new DataPlaneEvidence.Trace(List.of(source), DataPlaneEvidence.Disposition.NO_ROUTE));
      }
      walk(source.name(), new ArrayList<>(), new HashSet<>(), result);
      return result;
    }

    private void walk(
        String node,
        List<RouterId> path,
        Set<String> onPath,
        List<DataPlaneEvidence.Trace> result) {
      if (result.size() >= MAX_TRACES) {
        return;
      }
      path.add(RouterId.of(node));
      if (!onPath.add(node) || path.size() > MAX_HOPS) {
        result.add(new DataPlaneEvidence.Trace(path, DataPlaneEvidence.Disposition.LOOP));
        path.removeLast();
        return;
      }
      List<Edge> outgoing = edges.get(node);
      if (outgoing == null) {
        result.add(new DataPlaneEvidence.Trace(path, DataPlaneEvidence.Disposition.NO_ROUTE));
      } else {
        for (Edge edge : outgoing) {
          if (result.size() >= MAX_TRACES) {
            break;
          }
          if (isDevice(edge.target())) {
            walk(edge.target(), path, onPath, result);
          } else if (edge.action().equals(BLOCKED_INCOMING) && denials.containsKey(node)) {
            for (String receiver : denials.get(node)) {
              path.add(RouterId.of(receiver));
              result.add(new DataPlaneEvidence.Trace(path, DataPlaneEvidence.Disposition.DENIED));
              path.removeLast();
            }
          } else {
            result.add(new DataPlaneEvidence.Trace(path, disposition(edge)));
          }
        }
      }
      onPath.remove(node);
      path.removeLast();
    }

    private DataPlaneEvidence.Disposition disposition(Edge edge) {
      if (edge.target().equals(sink)) {
        return DataPlaneEvidence.Disposition.ACCEPTED;
      }
      if (edge.target().startsWith(INTERFACE) || edge.target().startsWith("next-hop:")) {
        return DataPlaneEvidence.Disposition.EXITS_NETWORK;
      }
      return switch (edge.action()) {
        case "null_routed" -> DataPlaneEvidence.Disposition.NULL_ROUTED;
        case "forwarded_blocked_outgoing_filter", "forwarded_blocked_incoming_filter" ->
            DataPlaneEvidence.Disposition.DENIED;
        case "no_route" -> DataPlaneEvidence.Disposition.NO_ROUTE;
        case "neighbor_unreachable" -> DataPlaneEvidence.Disposition.NEIGHBOR_UNREACHABLE;
        default -> DataPlaneEvidence.Disposition.OTHER;
      };
    }
  }
}
