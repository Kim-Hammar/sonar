package se.kth.sonar.network;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import se.kth.sonar.text.RouterId;

/** The routers of a network and the point-to-point links between their interfaces. */
public final class Topology {
  private final NavigableSet<RouterId> routers;
  private final List<Link> links;
  private final Map<RouterId, NavigableSet<RouterId>> neighbours;
  private final Map<Endpoint, Endpoint> peers;

  private Topology(
      NavigableSet<RouterId> routers,
      List<Link> links,
      Map<RouterId, NavigableSet<RouterId>> neighbours,
      Map<Endpoint, Endpoint> peers) {
    this.routers = routers;
    this.links = links;
    this.neighbours = neighbours;
    this.peers = peers;
  }

  /**
   * Builds a topology.
   *
   * @throws IllegalArgumentException if a link names an unknown router or an interface is on two
   *     links
   */
  public static Topology of(Collection<RouterId> routers, Collection<Link> links) {
    NavigableSet<RouterId> known = new TreeSet<>(routers);
    Map<RouterId, NavigableSet<RouterId>> neighbours = new TreeMap<>();
    Map<Endpoint, Endpoint> peers = new HashMap<>();
    for (Link link : links) {
      for (Endpoint end : List.of(link.a(), link.b())) {
        if (!known.contains(end.router())) {
          throw new IllegalArgumentException(
              "link " + link + " names unknown router " + end.router());
        }
      }
      if (peers.putIfAbsent(link.a(), link.b()) != null
          || peers.putIfAbsent(link.b(), link.a()) != null) {
        throw new IllegalArgumentException("an interface of " + link + " is on another link");
      }
      neighbours.computeIfAbsent(link.a().router(), r -> new TreeSet<>()).add(link.b().router());
      neighbours.computeIfAbsent(link.b().router(), r -> new TreeSet<>()).add(link.a().router());
    }
    Map<RouterId, NavigableSet<RouterId>> frozen = new TreeMap<>();
    neighbours.forEach(
        (router, set) -> frozen.put(router, Collections.unmodifiableNavigableSet(set)));
    return new Topology(
        Collections.unmodifiableNavigableSet(known),
        List.copyOf(links),
        Collections.unmodifiableMap(frozen),
        Collections.unmodifiableMap(peers));
  }

  /** Returns the routers in name order. */
  public NavigableSet<RouterId> routers() {
    return routers;
  }

  /** Returns the links in the order given. */
  public List<Link> links() {
    return links;
  }

  /** Returns the routers that share a link with {@code router}. */
  public NavigableSet<RouterId> neighbours(RouterId router) {
    return neighbours.getOrDefault(router, Collections.emptyNavigableSet());
  }

  /** Returns the other end of the link at {@code endpoint}, if there is one. */
  public Optional<Endpoint> peer(Endpoint endpoint) {
    return Optional.ofNullable(peers.get(endpoint));
  }

  /**
   * One interface of one router.
   *
   * @param router the router
   * @param interfaceName the interface name as written in the configuration
   */
  public record Endpoint(RouterId router, String interfaceName) {
    /** Checks the components. */
    public Endpoint {
      Objects.requireNonNull(router, "router");
      Objects.requireNonNull(interfaceName, "interfaceName");
    }
  }

  /**
   * A link between interfaces of two different routers.
   *
   * @param a one end
   * @param b the other end
   */
  public record Link(Endpoint a, Endpoint b) {
    /** Checks that the ends are on different routers. */
    public Link {
      Objects.requireNonNull(a, "a");
      Objects.requireNonNull(b, "b");
      if (a.router().equals(b.router())) {
        throw new IllegalArgumentException("a link joins two different routers: " + a + ", " + b);
      }
    }
  }
}
