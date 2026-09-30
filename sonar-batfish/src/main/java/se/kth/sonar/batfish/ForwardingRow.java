package se.kth.sonar.batfish;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;

/**
 * One route of one router's main RIB with the forwarding disposition of traffic to its prefix, as
 * the benchmark's forwarding behaviour question reports it.
 *
 * @param node the router, by its lower-case host name
 * @param prefix the route's network
 * @param nextHopIp the next-hop address, if the route has one
 * @param nextHopInterface the outgoing interface, after resolving the next hop through the RIB
 * @param action the disposition, such as {@code forwarded}, {@code accepted} or {@code null_routed}
 * @param receivers the neighbours whose inbound filter blocks the flow, when the action says so
 */
record ForwardingRow(
    String node,
    Prefix prefix,
    Optional<Ip> nextHopIp,
    Optional<String> nextHopInterface,
    String action,
    Set<String> receivers) {
  static final String FORWARDED = "forwarded";
  static final String ACCEPTED = "accepted";

  /** Checks the components and copies the receivers. */
  ForwardingRow {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(nextHopIp, "nextHopIp");
    Objects.requireNonNull(nextHopInterface, "nextHopInterface");
    Objects.requireNonNull(action, "action");
    receivers = Set.copyOf(receivers);
  }

  /** A row whose flow no neighbour blocks. */
  ForwardingRow(
      String node,
      Prefix prefix,
      Optional<Ip> nextHopIp,
      Optional<String> nextHopInterface,
      String action) {
    this(node, prefix, nextHopIp, nextHopInterface, action, Set.of());
  }
}
