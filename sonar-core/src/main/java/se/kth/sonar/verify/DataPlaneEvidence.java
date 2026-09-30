package se.kth.sonar.verify;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
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

  /** Returns a deterministic digest of every trace, route holder, and forwarder. */
  public ContentHash digest() {
    ContentHash.Builder digest = ContentHash.builder().add("traces").add(traces.size());
    traces.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(ForwardingPredicate::key)))
        .forEach(
            entry -> {
              digest.add(entry.getKey().key()).add(entry.getValue().size());
              for (Trace trace : entry.getValue()) {
                digest.add(trace.disposition().name()).add(trace.hops().size());
                trace.hops().forEach(router -> digest.add(router.name()));
              }
            });
    addMembership(digest, "route-holders", routeHolders);
    addMembership(digest, "forwarders", forwarders);
    return digest.build();
  }

  private static void addMembership(
      ContentHash.Builder digest, String label, Map<Prefix, Set<RouterId>> membership) {
    digest.add(label).add(membership.size());
    for (Prefix prefix : new TreeSet<>(membership.keySet())) {
      TreeSet<RouterId> routers = new TreeSet<>(membership.get(prefix));
      digest.add(prefix.toString()).add(routers.size());
      routers.forEach(router -> digest.add(router.name()));
    }
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
