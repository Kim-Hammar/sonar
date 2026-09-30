package se.kth.sonar.batfish;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One row of Batfish's output as the benchmark's forwarding behaviour question reports it: a route
 * of one router's main RIB with the disposition of traffic to its prefix.
 *
 * @param node the router, by its lower-case host name
 * @param prefix the route's network
 * @param nextHopIp the next-hop address, if the route has one
 * @param nextHopInterface the outgoing interface, after resolving the next hop through the RIB
 * @param action the disposition, such as {@code forwarded}, {@code accepted} or {@code null_routed}
 * @param receivers the neighbours whose inbound filter blocks the flow, when the action says so
 */
public record ForwardingEntry(
    String node,
    String prefix,
    Optional<String> nextHopIp,
    Optional<String> nextHopInterface,
    String action,
    Set<String> receivers) {
  /** Checks the components and copies the receivers. */
  public ForwardingEntry {
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(nextHopIp, "nextHopIp");
    Objects.requireNonNull(nextHopInterface, "nextHopInterface");
    Objects.requireNonNull(action, "action");
    receivers = Set.copyOf(receivers);
  }

  static ForwardingEntry of(ForwardingRow row) {
    return new ForwardingEntry(
        row.node(),
        row.prefix().toString(),
        row.nextHopIp().map(Object::toString),
        row.nextHopInterface(),
        row.action(),
        row.receivers());
  }
}
