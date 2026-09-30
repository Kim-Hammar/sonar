package se.kth.sonar.verify;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.text.RouterId;

/**
 * Data-plane facts that explain violated specifications and localise faults.
 *
 * @param traces how traffic for each specified predicate was forwarded
 * @param routeHolders the routers that hold a BGP route to each destination
 * @param forwarders the routers that have a forwarding entry for each destination
 */
public record DataPlaneEvidence(
    Map<ForwardingPredicate, List<Trace>> traces,
    Map<Prefix, Set<RouterId>> routeHolders,
    Map<Prefix, Set<RouterId>> forwarders) {

  /** Copies the maps. */
  public DataPlaneEvidence {
    traces = Map.copyOf(traces);
    routeHolders = Map.copyOf(routeHolders);
    forwarders = Map.copyOf(forwarders);
  }

  /** Returns evidence without facts. */
  public static DataPlaneEvidence none() {
    return new DataPlaneEvidence(Map.of(), Map.of(), Map.of());
  }

  /** The fate of a traced flow. */
  public enum Disposition {
    ACCEPTED,
    DENIED,
    NO_ROUTE,
    NULL_ROUTED,
    NEIGHBOR_UNREACHABLE,
    EXITS_NETWORK,
    LOOP,
    OTHER
  }

  /**
   * How one flow was forwarded.
   *
   * @param hops the routers the flow visited, in order
   * @param disposition what happened to the flow at the last hop
   */
  public record Trace(List<RouterId> hops, Disposition disposition) {
    /** Copies the hops. */
    public Trace {
      hops = List.copyOf(hops);
      Objects.requireNonNull(disposition, "disposition");
    }

    /** Returns the last router the flow visited. */
    public Optional<RouterId> last() {
      return hops.isEmpty() ? Optional.empty() : Optional.of(hops.getLast());
    }
  }
}
