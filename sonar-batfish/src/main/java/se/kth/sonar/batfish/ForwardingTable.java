package se.kth.sonar.batfish;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.ConcreteInterfaceAddress;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.FinalMainRib;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.ForwardingAnalysis;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.InterfaceForwardingBehavior;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpSpace;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.Route;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.Topology;
import org.batfish.datamodel.VrfForwardingBehavior;
import org.batfish.datamodel.collections.NodeInterfacePair;
import org.batfish.datamodel.route.nh.NextHop;
import org.batfish.datamodel.route.nh.NextHopDiscard;
import org.batfish.datamodel.route.nh.NextHopVrf;

/**
 * Computes the rows of the benchmark's forwarding behaviour question from a data plane: one row per
 * main-RIB route with its resolved next hop and a disposition read from the forwarding analysis,
 * then overridden by the access lists on the way out and into every layer-3 neighbour. The rules
 * follow the question's answerer in the benchmark's Batfish build exactly, so the predicates mined
 * from the rows match the benchmark's labels.
 */
final class ForwardingTable {
  private static final int MAX_RESOLUTION_DEPTH = 10;
  private static final Comparator<AbstractRoute> ROUTE_ORDER =
      Comparator.comparing(AbstractRoute::getNetwork)
          .thenComparing(route -> interfaceOf(route).orElse(""))
          .thenComparing(route -> ipOf(route).map(Ip::asLong).orElse(Long.MIN_VALUE))
          .thenComparing(
              route ->
                  Optional.ofNullable(route.getProtocol())
                      .map(RoutingProtocol::protocolName)
                      .orElse(""));

  private ForwardingTable() {}

  /** Returns the rows of every node and VRF of {@code dataPlane}, in node, VRF and route order. */
  static List<ForwardingRow> rows(
      DataPlane dataPlane, Map<String, Configuration> configurations, Topology layer3) {
    ForwardingAnalysis analysis = dataPlane.getForwardingAnalysis();
    List<ForwardingRow> rows = new ArrayList<>();
    for (String node : new TreeSet<>(dataPlane.getRibs().rowKeySet())) {
      Map<String, FinalMainRib> vrfs = dataPlane.getRibs().row(node);
      Map<String, VrfForwardingBehavior> behaviours =
          analysis.getVrfForwardingBehavior().getOrDefault(node, Map.of());
      Configuration configuration = configurations.get(node);
      Map<String, IpSpace> namedIpSpaces =
          configuration == null ? Map.of() : configuration.getIpSpaces();
      for (String vrf : new TreeSet<>(vrfs.keySet())) {
        FinalMainRib rib = vrfs.get(vrf);
        if (rib == null) {
          continue;
        }
        for (AbstractRoute route : rib.getRoutes().stream().sorted(ROUTE_ORDER).toList()) {
          List<Ip> candidates = candidateIps(route.getNetwork());
          Hop hop = resolve(route, rib);
          String action = action(behaviours.get(vrf), namedIpSpaces, hop, route, candidates);
          Filtered filtered =
              aclOverride(node, hop, candidates, configurations, namedIpSpaces, layer3)
                  .orElse(new Filtered(action, Set.of()));
          rows.add(
              new ForwardingRow(
                  node,
                  route.getNetwork(),
                  Optional.ofNullable(hop.nextHopIp),
                  Optional.ofNullable(hop.interfaceName),
                  filtered.action(),
                  filtered.receivers()));
        }
      }
    }
    return rows;
  }

  private static final class Hop {
    private String interfaceName;
    private Ip nextHopIp;
    private boolean delegatedToVrf;

    private Hop(String interfaceName, Ip nextHopIp) {
      this.interfaceName = interfaceName;
      this.nextHopIp = nextHopIp;
    }
  }

  private static Hop resolve(AbstractRoute route, FinalMainRib rib) {
    Hop hop = new Hop(interfaceOf(route).orElse(null), ipOf(route).orElse(null));
    if (route.getNextHop() instanceof NextHopVrf) {
      hop.delegatedToVrf = true;
      return hop;
    }
    if (hop.interfaceName != null || hop.nextHopIp == null) {
      return hop;
    }
    Ip original = hop.nextHopIp;
    Ip current = original;
    Set<Ip> visited = new HashSet<>();
    for (int depth = 0; depth < MAX_RESOLUTION_DEPTH; depth++) {
      if (!visited.add(current)) {
        return hop;
      }
      List<AbstractRoute> matches =
          rib.longestPrefixMatch(current).stream().sorted(ROUTE_ORDER).toList();
      if (matches.isEmpty()) {
        return hop;
      }
      Optional<AbstractRoute> terminating =
          matches.stream().filter(match -> interfaceOf(match).isPresent()).findFirst();
      if (terminating.isPresent()) {
        hop.interfaceName = interfaceOf(terminating.get()).orElseThrow();
        hop.nextHopIp = original;
        return hop;
      }
      Optional<Ip> next =
          matches.stream().map(ForwardingTable::ipOf).flatMap(Optional::stream).findFirst();
      if (next.isEmpty()) {
        return hop;
      }
      current = next.get();
    }
    return hop;
  }

  private static String action(
      VrfForwardingBehavior behaviour,
      Map<String, IpSpace> namedIpSpaces,
      Hop hop,
      AbstractRoute route,
      List<Ip> candidates) {
    if (hop.delegatedToVrf) {
      return "delegated_to_vrf";
    }
    NextHop nextHop = route.getNextHop();
    if (nextHop instanceof NextHopDiscard) {
      return "null_routed";
    }
    String interfaceName = hop.interfaceName;
    if (interfaceName != null) {
      String lowered = interfaceName.toLowerCase(Locale.US);
      if (lowered.startsWith("loopback")) {
        return ForwardingRow.ACCEPTED;
      }
      if (lowered.startsWith("null") || "null_interface".equalsIgnoreCase(interfaceName)) {
        return "null_routed";
      }
    }
    String routeClass = route.getClass().getSimpleName();
    if (route.getProtocol() == RoutingProtocol.LOCAL || routeClass.endsWith("LocalRoute")) {
      return ForwardingRow.ACCEPTED;
    }
    if (route.getProtocol() == RoutingProtocol.CONNECTED || routeClass.endsWith("ConnectedRoute")) {
      return "delivered_to_subnet";
    }
    InterfaceForwardingBehavior perInterface =
        behaviour != null && interfaceName != null
            ? behaviour.getInterfaceForwardingBehavior().get(interfaceName)
            : null;
    if (perInterface != null) {
      if (containsAny(perInterface.getDeliveredToSubnet(), candidates, namedIpSpaces)) {
        return "delivered_to_subnet";
      }
      if (containsAny(perInterface.getAcceptedIps(), candidates, namedIpSpaces)) {
        return ForwardingRow.ACCEPTED;
      }
      if (containsAny(perInterface.getExitsNetwork(), candidates, namedIpSpaces)) {
        return ForwardingRow.FORWARDED;
      }
      if (containsAny(perInterface.getInsufficientInfo(), candidates, namedIpSpaces)) {
        return "insufficient_info";
      }
      if (containsAny(perInterface.getNeighborUnreachable(), candidates, namedIpSpaces)) {
        return "neighbor_unreachable";
      }
    }
    if (behaviour != null) {
      if (containsAny(behaviour.getNullRoutedIps(), candidates, namedIpSpaces)) {
        return "null_routed";
      }
      if (!containsAny(behaviour.getRoutableIps(), candidates, namedIpSpaces)) {
        return "no_route";
      }
    }
    return interfaceName != null ? ForwardingRow.FORWARDED : "unknown";
  }

  /** A flow blocked by an access list, and by whose inbound filter when a neighbour's. */
  private record Filtered(String action, Set<String> receivers) {}

  private static Optional<Filtered> aclOverride(
      String node,
      Hop hop,
      List<Ip> candidates,
      Map<String, Configuration> configurations,
      Map<String, IpSpace> namedIpSpaces,
      Topology layer3) {
    Configuration configuration = configurations.get(node);
    if (hop.interfaceName == null || configuration == null) {
      return Optional.empty();
    }
    Interface outgoing = configuration.getAllInterfaces().get(hop.interfaceName);
    if (outgoing == null) {
      return Optional.empty();
    }
    ConcreteInterfaceAddress address = outgoing.getConcreteAddress();
    Ip source = address == null ? Ip.ZERO : address.getIp();
    IpAccessList outgoingFilter = outgoing.getOutgoingFilter();
    if (outgoingFilter != null
        && deniesAll(
            outgoingFilter,
            node,
            hop.interfaceName,
            source,
            candidates,
            configuration.getIpAccessLists(),
            namedIpSpaces)) {
      return Optional.of(new Filtered("forwarded_blocked_outgoing_filter", Set.of()));
    }
    SortedSet<NodeInterfacePair> neighbours =
        layer3.getNeighbors(NodeInterfacePair.of(node, hop.interfaceName));
    if (neighbours.isEmpty()) {
      return Optional.empty();
    }
    Set<String> receivers = new TreeSet<>();
    for (NodeInterfacePair neighbour : neighbours) {
      Configuration neighbourConfiguration = configurations.get(neighbour.getHostname());
      if (neighbourConfiguration == null) {
        return Optional.empty();
      }
      Interface incoming = neighbourConfiguration.getAllInterfaces().get(neighbour.getInterface());
      if (incoming == null || incoming.getIncomingFilter() == null) {
        return Optional.empty();
      }
      if (!deniesAll(
          incoming.getIncomingFilter(),
          neighbour.getHostname(),
          neighbour.getInterface(),
          source,
          candidates,
          neighbourConfiguration.getIpAccessLists(),
          neighbourConfiguration.getIpSpaces())) {
        return Optional.empty();
      }
      receivers.add(neighbour.getHostname());
    }
    return Optional.of(new Filtered("forwarded_blocked_incoming_filter", receivers));
  }

  private static boolean deniesAll(
      IpAccessList acl,
      String ingressNode,
      String ingressInterface,
      Ip source,
      List<Ip> candidates,
      Map<String, IpAccessList> acls,
      Map<String, IpSpace> namedIpSpaces) {
    for (Ip destination : candidates) {
      Flow flow =
          Flow.builder()
              .setIngressNode(ingressNode)
              .setIngressInterface(ingressInterface)
              .setSrcIp(source)
              .setDstIp(destination)
              .build();
      if (acl.filter(flow, ingressInterface, acls, namedIpSpaces).getAction()
          == LineAction.PERMIT) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsAny(
      IpSpace space, List<Ip> candidates, Map<String, IpSpace> named) {
    return space != null && candidates.stream().anyMatch(ip -> space.containsIp(ip, named));
  }

  /** Returns the sample addresses of {@code prefix}: its ends, their neighbours and its middle. */
  static List<Ip> candidateIps(Prefix prefix) {
    Set<Ip> candidates = new LinkedHashSet<>();
    long start = prefix.getStartIp().asLong();
    long end = prefix.getEndIp().asLong();
    long size = end - start + 1;
    candidates.add(prefix.getStartIp());
    if (size > 1) {
      candidates.add(Ip.create(Math.min(end, start + 1)));
    }
    if (size > 2) {
      candidates.add(Ip.create(Math.max(start, end - 1)));
    }
    long middle = start + size / 2;
    if (middle >= start && middle <= end) {
      candidates.add(Ip.create(middle));
    }
    candidates.add(prefix.getEndIp());
    return new ArrayList<>(candidates);
  }

  private static Optional<String> interfaceOf(AbstractRoute route) {
    String name = route.getNextHopInterface();
    return name == null || name.equals(Route.UNSET_NEXT_HOP_INTERFACE)
        ? Optional.empty()
        : Optional.of(name);
  }

  private static Optional<Ip> ipOf(AbstractRoute route) {
    Ip ip = route.getNextHopIp();
    return ip == null || !ip.valid() || ip.equals(Route.UNSET_ROUTE_NEXT_HOP_IP)
        ? Optional.empty()
        : Optional.of(ip);
  }
}
