package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Ipv4;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

/**
 * The semantics of the Cisco IOS dialect that its relation schema declares as derived: which router
 * owns an address, the subnet of an interface, the OSPF area an interface ends up in, the interface
 * at the other end of a link, the remote AS a neighbor inherits from its peer group, the prefix of
 * a network, route or OSPF network statement, the AS a router's name assigns it, and a few more. It
 * also knows how to realise a wanted subnet, area, remote AS, peer router, coverage or prefix as
 * text. An OSPF network statement wide enough for several interfaces covers the one with the lowest
 * address.
 */
public final class CiscoIosSemantics implements ProgramModel.Factory {
  private static final Set<String> FUNCTIONS =
      Set.of(
          "address-owner",
          "design-asn",
          "effective-remote-as",
          "igp-enabled",
          "igp-foreign-peer",
          "igp-peer",
          "isis-area",
          "isis-attached",
          "isis-level-1",
          "link-peer",
          "loopback-address",
          "loopback-wildcard",
          "ospf-coverage",
          "ospf-loopback-required",
          "network-prefix",
          "ospf-area",
          "route-map-export-attributes",
          "route-map-import-attributes",
          "route-map-path-attributes",
          "route-map-use",
          "subnet",
          "stub-prefix",
          "wildcard-prefix");

  /**
   * The BGP path attributes a clause can set. Only a BGP session carries them, so a map that sets
   * one is a session policy, and redistribution into an IGP would apply it for nothing.
   */
  private static final Set<String> PATH_ATTRIBUTES =
      Set.of("local-preference", "as-path-prepend", "communities");

  /**
   * What a clause sets for the router's own choice among routes: a local preference ranks routes
   * inside the receiving system and does not cross an external session, so it belongs on import.
   */
  private static final Set<String> IMPORT_ATTRIBUTES = Set.of("local-preference");

  /**
   * What a clause sets for the neighbor's choice among routes: a metric and a prepended AS path
   * speak to the system that receives the route, so they belong on export.
   */
  private static final Set<String> EXPORT_ATTRIBUTES = Set.of("metric", "as-path-prepend");

  private static final Pattern NAME_ASN = Pattern.compile("_as(\\d+)_");
  private static final int MAX_HOSTS = 4;
  private static final int NET_SUFFIX_GROUPS = 4;

  private static final int CACHE_CAPACITY = 4096;
  private static final int TOPOLOGY_CAPACITY = 64;

  private final Dialect dialect;
  private final Map<String, List<Derivation>> derivations;
  private final IdentityHashMap<Topology, IdentityHashMap<List<Statement>, RouterFacts>> facts =
      new IdentityHashMap<>();
  private final Map<RouterId, String> routerRefs = new ConcurrentHashMap<>();

  /** A derived attribute of an object type and the function that computes it. */
  private record Derivation(String attribute, String function) {}

  /**
   * What one router's configuration contributes to the derived attributes: the values that depend
   * on the router's own objects, statements and the topology only, keyed by object; the addresses
   * its interfaces own; and the peer addresses its BGP neighbors name, whose owning router is found
   * across the network.
   */
  private record RouterFacts(
      Map<ProgramObject.Ref, Map<String, List<String>>> local,
      List<Ipv4> addresses,
      List<PeerLookup> peers) {}

  private record PeerLookup(ProgramObject.Ref neighbor, String attribute, Ipv4 peer) {}

  /** Creates the semantics of {@code dialect}, which must be the Cisco IOS dialect. */
  public CiscoIosSemantics(Dialect dialect) {
    this.dialect = java.util.Objects.requireNonNull(dialect, "dialect");
    Map<String, List<Derivation>> derivations = new HashMap<>();
    for (ObjectType type : dialect.schema().objects()) {
      List<Derivation> list = new ArrayList<>();
      for (Attribute attribute : type.attributes().values()) {
        if (attribute.source() instanceof Attribute.Source.Derived derived) {
          list.add(new Derivation(attribute.name(), derived.function()));
        }
      }
      if (!list.isEmpty()) {
        derivations.put(type.name(), List.copyOf(list));
      }
    }
    this.derivations = Map.copyOf(derivations);
  }

  @Override
  public Set<String> functions() {
    return FUNCTIONS;
  }

  @Override
  public Map<ProgramObject.Ref, Map<String, List<String>>> derive(
      Topology topology, Map<RouterId, List<Statement>> statements, List<ProgramObject> objects) {
    Map<RouterId, List<ProgramObject>> byRouter = new LinkedHashMap<>();
    for (ProgramObject object : objects) {
      byRouter.computeIfAbsent(object.ref().router(), router -> new ArrayList<>()).add(object);
    }
    List<RouterFacts> all = new ArrayList<>(byRouter.size());
    Map<Ipv4, List<RouterId>> owners = new HashMap<>();
    byRouter.forEach(
        (router, own) -> {
          RouterFacts facts = facts(topology, statements.getOrDefault(router, List.of()), own);
          all.add(facts);
          for (Ipv4 address : facts.addresses()) {
            owners.computeIfAbsent(address, a -> new ArrayList<>(1)).add(router);
          }
        });
    Map<ProgramObject.Ref, Map<String, List<String>>> derived = new HashMap<>(objects.size() * 2);
    for (RouterFacts facts : all) {
      derived.putAll(facts.local());
      for (PeerLookup lookup : facts.peers()) {
        List<RouterId> owning = owners.get(lookup.peer());
        if (owning != null && owning.size() == 1) {
          Map<String, List<String>> values =
              new LinkedHashMap<>(derived.getOrDefault(lookup.neighbor(), Map.of()));
          values.put(lookup.attribute(), List.of(routerRef(owning.getFirst())));
          derived.put(lookup.neighbor(), values);
        }
      }
    }
    addIgpPeers(topology, objects, derived);
    addIsisAttachment(objects, derived);
    return derived;
  }

  /**
   * Marks every IS-IS router whose area has a way out: a router of the area that also runs level 2,
   * which is what gives the level-1 routers of the area their route to everything beyond it. A
   * router that is not confined to level 1 is such a router for its own area.
   */
  private static void addIsisAttachment(
      List<ProgramObject> objects, Map<ProgramObject.Ref, Map<String, List<String>>> derived) {
    Map<ProgramObject, List<String>> areas = new LinkedHashMap<>();
    Set<String> attached = new HashSet<>();
    for (ProgramObject object : objects) {
      if (!object.ref().type().equals(ObjectType.ROUTER) || !object.has("isis-process")) {
        continue;
      }
      List<String> area =
          derived.getOrDefault(object.ref(), Map.of()).getOrDefault("isis-area", List.of());
      areas.put(object, area);
      String level = object.value("isis-level").orElse("level-1-2");
      if (level.equals("level-1-2")) {
        attached.addAll(area);
      }
    }
    areas.forEach(
        (router, area) -> {
          if (router.value("isis-level").filter("level-1"::equals).isEmpty()
              || area.stream().anyMatch(attached::contains)) {
            Map<String, List<String>> values =
                new LinkedHashMap<>(derived.getOrDefault(router.ref(), Map.of()));
            values.put("isis-attached", List.of("true"));
            derived.put(router.ref(), Map.copyOf(values));
          }
        });
  }

  /**
   * Adds the peer of every interface whose physical link is typed as an IGP relation. A design-AS
   * equality types the ordinary intra-domain case. Across design domains, a configured protocol on
   * one of the link endpoints is required; router-wide process declarations alone do not make every
   * external link an IGP adjacency. Such a peer across domains is a foreign peer: an IGP adjacency
   * there is the exception, so a link that has one on one end only is as likely to lose it there as
   * to gain it on the other.
   */
  private static void addIgpPeers(
      Topology topology,
      List<ProgramObject> objects,
      Map<ProgramObject.Ref, Map<String, List<String>>> derived) {
    Map<RouterId, ProgramObject> routers = new HashMap<>();
    Map<ProgramObject.Ref, ProgramObject> byRef = new HashMap<>();
    for (ProgramObject object : objects) {
      byRef.put(object.ref(), object);
      if (object.ref().type().equals(ObjectType.ROUTER)) {
        routers.put(object.ref().router(), object);
      }
    }
    for (ProgramObject object : objects) {
      if (!object.ref().type().equals("interface")) {
        continue;
      }
      Topology.Endpoint local =
          new Topology.Endpoint(object.ref().router(), object.ref().key().getFirst());
      Optional<Topology.Endpoint> peer = topology.peer(local);
      if (peer.isEmpty()) {
        continue;
      }
      ProgramObject.Ref peerRef =
          new ProgramObject.Ref(
              "interface", peer.get().router(), List.of(peer.get().interfaceName()));
      ProgramObject peerInterface = byRef.get(peerRef);
      if (peerInterface == null
          || !igpCompatible(
              routers.get(local.router()),
              routers.get(peer.get().router()),
              object,
              peerInterface,
              derived)) {
        continue;
      }
      Map<String, List<String>> values =
          new LinkedHashMap<>(derived.getOrDefault(object.ref(), Map.of()));
      boolean foreign =
          designAsn(local.router())
              .flatMap(asn -> designAsn(peer.get().router()).filter(other -> !other.equals(asn)))
              .isPresent();
      values.put(foreign ? "igp-foreign-peer" : "igp-peer", List.of(peerRef.toString()));
      derived.put(object.ref(), Map.copyOf(values));
    }
  }

  private static boolean igpCompatible(
      ProgramObject leftRouter,
      ProgramObject rightRouter,
      ProgramObject leftInterface,
      ProgramObject rightInterface,
      Map<ProgramObject.Ref, Map<String, List<String>>> derived) {
    if (leftRouter == null || rightRouter == null) {
      return false;
    }
    boolean sameDesignAs =
        designAsn(leftRouter.ref().router())
            .flatMap(asn -> designAsn(rightRouter.ref().router()).filter(asn::equals))
            .isPresent();
    if (sameDesignAs) {
      return true;
    }
    boolean ospfProcess = leftRouter.has("ospf-process") && rightRouter.has("ospf-process");
    boolean ospfEndpoint =
        hasDerived(leftInterface, "ospf-effective-area", derived)
            || hasDerived(rightInterface, "ospf-effective-area", derived);
    boolean isisProcess = leftRouter.has("isis-process") && rightRouter.has("isis-process");
    boolean isisEndpoint = leftInterface.has("isis-enabled") || rightInterface.has("isis-enabled");
    return (ospfProcess && ospfEndpoint) || (isisProcess && isisEndpoint);
  }

  private static boolean hasDerived(
      ProgramObject object,
      String attribute,
      Map<ProgramObject.Ref, Map<String, List<String>>> derived) {
    return !derived
        .getOrDefault(object.ref(), Map.of())
        .getOrDefault(attribute, List.of())
        .isEmpty();
  }

  private String routerRef(RouterId router) {
    return routerRefs.computeIfAbsent(
        router, r -> new ProgramObject.Ref(ObjectType.ROUTER, r, List.of()).toString());
  }

  /**
   * Returns the facts of a router, remembered by the identity of its statement list, which the
   * program service keeps for every parsed configuration, and of the topology.
   */
  private RouterFacts facts(
      Topology topology, List<Statement> statements, List<ProgramObject> objects) {
    IdentityHashMap<List<Statement>, RouterFacts> known;
    synchronized (facts) {
      if (facts.size() > TOPOLOGY_CAPACITY) {
        facts.clear();
      }
      known = facts.computeIfAbsent(topology, t -> new IdentityHashMap<>());
      RouterFacts found = known.get(statements);
      if (found != null) {
        return found;
      }
    }
    RouterFacts computed = computeFacts(topology, statements, objects);
    synchronized (facts) {
      if (known.size() > CACHE_CAPACITY) {
        known.clear();
      }
      known.put(statements, computed);
    }
    return computed;
  }

  private RouterFacts computeFacts(
      Topology topology, List<Statement> statements, List<ProgramObject> objects) {
    Map<ProgramObject.Ref, ProgramObject> byRef = new HashMap<>(objects.size() * 2);
    objects.forEach(object -> byRef.put(object.ref(), object));
    List<OspfNetwork> networks = ospfNetworks(statements);
    Map<ProgramObject.Ref, Map<String, List<String>>> local = new HashMap<>();
    List<Ipv4> addresses = new ArrayList<>();
    List<PeerLookup> peers = new ArrayList<>();
    for (ProgramObject object : objects) {
      if (object.ref().type().equals("interface")) {
        object.value("address").flatMap(Ipv4::tryParse).ifPresent(addresses::add);
      }
      Map<String, List<String>> values = new LinkedHashMap<>();
      for (Derivation derivation : derivations.getOrDefault(object.ref().type(), List.of())) {
        if (derivation.function().equals("address-owner")) {
          object
              .value("peer")
              .or(() -> object.value("next-hop"))
              .flatMap(Ipv4::tryParse)
              .ifPresent(
                  peer -> peers.add(new PeerLookup(object.ref(), derivation.attribute(), peer)));
          continue;
        }
        List<String> value = compute(derivation.function(), object, topology, networks, byRef);
        if (!value.isEmpty()) {
          values.put(derivation.attribute(), value);
        }
      }
      if (!values.isEmpty()) {
        local.put(object.ref(), Map.copyOf(values));
      }
    }
    return new RouterFacts(Map.copyOf(local), List.copyOf(addresses), List.copyOf(peers));
  }

  private static List<String> compute(
      String function,
      ProgramObject object,
      Topology topology,
      List<OspfNetwork> ospfNetworks,
      Map<ProgramObject.Ref, ProgramObject> byRef) {
    RouterId router = object.ref().router();
    return switch (function) {
      case "subnet" -> prefixOf(object.value("address"), object.value("mask"));
      case "stub-prefix" -> {
        Topology.Endpoint endpoint = new Topology.Endpoint(router, object.ref().key().getFirst());
        Optional<Prefix> prefix =
            prefixOf(object.value("address"), object.value("mask")).stream()
                .map(Prefix::tryParse)
                .flatMap(Optional::stream)
                .filter(candidate -> candidate.length() < 32)
                .findFirst();
        yield topology.peer(endpoint).isEmpty() && prefix.isPresent()
            ? List.of(prefix.get().toString())
            : List.of();
      }
      case "network-prefix" -> prefixOf(object.value("network"), object.value("mask"));
      case "wildcard-prefix" ->
          wildcardPrefix(object.value("network"), object.value("wildcard"))
              .map(Prefix::toString)
              .map(List::of)
              .orElse(List.of());
      case "link-peer" ->
          topology
              .peer(new Topology.Endpoint(router, object.ref().key().getFirst()))
              .map(
                  end ->
                      new ProgramObject.Ref("interface", end.router(), List.of(end.interfaceName()))
                          .toString())
              .map(List::of)
              .orElse(List.of());
      case "igp-peer", "igp-foreign-peer", "isis-attached" -> List.of();
      case "effective-remote-as" -> {
        if (object.has("remote-as")) {
          yield object.values("remote-as");
        }
        yield object
            .value("peer-group")
            .map(group -> byRef.get(new ProgramObject.Ref("peer-group", router, List.of(group))))
            .map(group -> group.values("remote-as"))
            .orElse(List.of());
      }
      case "ospf-area" -> {
        if (object.has("ospf-area")) {
          yield object.values("ospf-area");
        }
        Optional<Ipv4> address = object.value("address").flatMap(Ipv4::tryParse);
        if (address.isEmpty()) {
          yield List.of();
        }
        OspfNetwork best = null;
        for (OspfNetwork network : ospfNetworks) {
          if (network.prefix().contains(address.get())
              && (best == null || network.prefix().length() > best.prefix().length())) {
            best = network;
          }
        }
        yield best == null ? List.of() : List.of(best.area());
      }
      case "design-asn" -> designAsn(router).map(List::of).orElse(List.of());
      case "isis-area" ->
          object.value("isis-net").flatMap(net -> isisArea(net)).map(List::of).orElse(List.of());
      case "igp-enabled" -> {
        if (object.has("isis-enabled")) {
          yield List.of("true");
        }
        boolean ospf = object.has("ospf-area");
        if (!ospf) {
          Optional<Ipv4> address = object.value("address").flatMap(Ipv4::tryParse);
          ospf =
              address.isPresent()
                  && ospfNetworks.stream().anyMatch(n -> n.prefix().contains(address.get()));
        }
        yield ospf ? List.of("true") : List.of();
      }
      case "isis-level-1" -> {
        if (!object.has("isis-enabled")) {
          yield List.of();
        }
        ProgramObject owner =
            byRef.get(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()));
        boolean levelOne =
            object.value("isis-circuit-type").filter("level-1"::equals).isPresent()
                || (owner != null
                    && owner.value("isis-level").filter("level-1"::equals).isPresent());
        yield levelOne ? List.of("true") : List.of();
      }
      case "loopback-address" -> {
        ProgramObject lowest = lowestLoopback(byRef, router).orElse(null);
        yield lowest == null ? List.of() : lowest.values("address");
      }
      case "loopback-wildcard" ->
          lowestLoopback(byRef, router).isPresent() ? List.of("0.0.0.0") : List.of();
      case "ospf-loopback-required" ->
          object.has("ospf-process") && lowestLoopback(byRef, router).isPresent()
              ? List.of("true")
              : List.of();
      case "ospf-coverage" -> {
        Optional<Prefix> prefix = wildcardPrefix(object.value("network"), object.value("wildcard"));
        if (prefix.isEmpty()) {
          yield List.of();
        }
        ProgramObject covered = null;
        Ipv4 lowest = null;
        for (ProgramObject candidate : byRef.values()) {
          if (!candidate.ref().type().equals("interface")) {
            continue;
          }
          Optional<Ipv4> address =
              candidate.value("address").flatMap(Ipv4::tryParse).filter(prefix.get()::contains);
          if (address.isPresent() && (lowest == null || address.get().compareTo(lowest) < 0)) {
            covered = candidate;
            lowest = address.get();
          }
        }
        yield covered == null ? List.of() : List.of(covered.ref().toString());
      }
      case "route-map-export-attributes" ->
          sets(object, byRef, EXPORT_ATTRIBUTES) ? List.of("true") : List.of();
      case "route-map-import-attributes" ->
          sets(object, byRef, IMPORT_ATTRIBUTES) ? List.of("true") : List.of();
      case "route-map-path-attributes" ->
          sets(object, byRef, PATH_ATTRIBUTES) ? List.of("true") : List.of();
      case "route-map-use" -> routeMapUsed(object, byRef) ? List.of("true") : List.of();
      default -> throw new IllegalArgumentException("unknown derived function " + function);
    };
  }

  /** Returns the first numbered loopback with an address on {@code router}. */
  private static Optional<ProgramObject> lowestLoopback(
      Map<ProgramObject.Ref, ProgramObject> objects, RouterId router) {
    ProgramObject lowest = null;
    for (ProgramObject candidate : objects.values()) {
      if (candidate.ref().router().equals(router)
          && candidate.ref().type().equals("interface")
          && candidate.ref().key().getFirst().startsWith("Loopback")
          && candidate.has("address")
          && (lowest == null
              || candidate.ref().key().getFirst().compareTo(lowest.ref().key().getFirst()) < 0)) {
        lowest = candidate;
      }
    }
    return Optional.ofNullable(lowest);
  }

  /** Returns whether a clause of the route map sets one of {@code attributes}. */
  private static boolean sets(
      ProgramObject routeMap,
      Map<ProgramObject.Ref, ProgramObject> objects,
      Set<String> attributes) {
    String name = routeMap.ref().key().getFirst();
    for (ProgramObject object : objects.values()) {
      if (object.ref().type().equals("route-map-entry")
          && object.ref().router().equals(routeMap.ref().router())
          && object.ref().key().getFirst().equals(name)
          && attributes.stream().anyMatch(object::has)) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether a route map has a typed control-plane use on its router. */
  private static boolean routeMapUsed(
      ProgramObject routeMap, Map<ProgramObject.Ref, ProgramObject> objects) {
    String name = routeMap.ref().key().getFirst();
    for (ProgramObject object : objects.values()) {
      if (object.ref().type().equals("bgp-neighbor")
          && (object.values("route-map-in").contains(name)
              || object.values("route-map-out").contains(name))) {
        return true;
      }
      if (object.ref().type().equals(ObjectType.ROUTER)
          && object.values("ospf-redistribution").contains(name)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the AS a router's name assigns it, if the name follows the naming convention. */
  static Optional<String> designAsn(RouterId router) {
    Matcher matcher = NAME_ASN.matcher(router.name());
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  /**
   * Returns the area of an IS-IS network entity title: everything before the six-byte system
   * identifier and the selector, which take the last four dotted groups.
   */
  static Optional<String> isisArea(String net) {
    List<String> groups = List.of(net.split("\\.", -1));
    return groups.size() > NET_SUFFIX_GROUPS
        ? Optional.of(String.join(".", groups.subList(0, groups.size() - NET_SUFFIX_GROUPS)))
        : Optional.empty();
  }

  /** Returns the prefix an OSPF network statement covers, or nothing if it is malformed. */
  static Optional<Prefix> wildcardPrefix(Optional<String> network, Optional<String> wildcard) {
    Optional<Ipv4> address = network.flatMap(Ipv4::tryParse);
    Optional<Ipv4> mask = wildcard.flatMap(Ipv4::tryParse);
    if (address.isEmpty() || mask.isEmpty() || mask.get().wildcardLength().isEmpty()) {
      return Optional.empty();
    }
    int length = mask.get().wildcardLength().getAsInt();
    return Optional.of(new Prefix(address.get().and(Ipv4.mask(length)), length));
  }

  /** Returns the prefix of an address and a mask, in a list, or an empty list. */
  static List<String> prefixOf(Optional<String> address, Optional<String> mask) {
    Optional<Prefix> prefix = prefix(address, mask);
    return prefix.map(Prefix::toString).map(List::of).orElse(List.of());
  }

  static Optional<Prefix> prefix(Optional<String> address, Optional<String> mask) {
    if (address.isEmpty() || mask.isEmpty()) {
      return Optional.empty();
    }
    Optional<Ipv4> parsedAddress = Ipv4.tryParse(address.get());
    Optional<Ipv4> parsedMask = Ipv4.tryParse(mask.get());
    if (parsedAddress.isEmpty() || parsedMask.isEmpty()) {
      return Optional.empty();
    }
    return parsedMask.get().maskLength().stream()
        .mapToObj(length -> new Prefix(parsedAddress.get().and(parsedMask.get()), length))
        .findFirst();
  }

  private record OspfNetwork(Prefix prefix, String area, SourceSpan statement) {}

  private List<OspfNetwork> ospfNetworks(List<Statement> statements) {
    Production production = dialect.grammar().production("ospf-network").orElseThrow();
    List<OspfNetwork> result = new ArrayList<>();
    for (Statement statement : statements) {
      Production.Match match = statement.match().orElse(null);
      if (match == null || !match.production().equals(production)) {
        continue;
      }
      Optional<Ipv4> network = Ipv4.tryParse(match.value("network"));
      Optional<Ipv4> wildcard = Ipv4.tryParse(match.value("wildcard"));
      if (network.isEmpty() || wildcard.isEmpty()) {
        continue;
      }
      wildcard
          .get()
          .wildcardLength()
          .ifPresent(
              length -> {
                Prefix prefix = new Prefix(network.get().and(Ipv4.mask(length)), length);
                result.add(new OspfNetwork(prefix, match.value("area"), statement.span()));
              });
    }
    return List.copyOf(result);
  }

  @Override
  public List<ProgramModel.Realisation> realise(
      String function, String target, ProgramObject object, ProgramModel program) {
    return switch (function) {
      case "subnet" -> subnetRealisations(target, object, program);
      case "network-prefix" -> {
        Optional<Prefix> prefix = Prefix.tryParse(target);
        if (prefix.isEmpty()) {
          yield List.of();
        }
        List<ProgramModel.Realisation> result = new ArrayList<>();
        for (Production source :
            dialect.schema().object(object.ref().type()).orElseThrow().sources()) {
          result.add(
              new ProgramModel.Realisation(
                  source,
                  Map.of(
                      "network", List.of(prefix.get().network().toString()),
                      "mask", List.of(prefix.get().mask().toString()))));
        }
        yield result;
      }
      case "wildcard-prefix" ->
          Prefix.tryParse(target)
              .map(
                  prefix ->
                      List.of(
                          new ProgramModel.Realisation(
                              dialect.grammar().production("ospf-network").orElseThrow(),
                              Map.of(
                                  "network", List.of(prefix.network().toString()),
                                  "wildcard",
                                      List.of(new Ipv4(~prefix.mask().bits()).toString())))))
              .orElse(List.of());
      case "ospf-area" -> areaRealisations(target, object, program);
      case "effective-remote-as" -> remoteAsRealisations(target, object, program);
      case "isis-area" -> {
        Optional<String> net = object.value("isis-net");
        if (net.isEmpty() || isisArea(net.get()).isEmpty()) {
          yield List.of();
        }
        List<String> groups = List.of(net.get().split("\\.", -1));
        String suffix =
            String.join(".", groups.subList(groups.size() - NET_SUFFIX_GROUPS, groups.size()));
        yield List.of(
            new ProgramModel.Realisation(
                dialect.grammar().production("isis-net").orElseThrow(),
                Map.of("net", List.of(target + "." + suffix))));
      }
      case "address-owner" -> peerRealisations(target, object, program);
      case "loopback-address" -> loopbackRealisations(target, object, program);
      case "ospf-coverage" -> coverageRealisations(target, object, program);
      case "igp-enabled" ->
          target.equals("false")
              ? igpExclusions(object, program)
              : igpRealisations(object, program);
      case "isis-attached" -> isisAttachmentRealisations(object, program);
      case "isis-level-1" -> isisLevelOneRealisations(target, object, program);
      case "route-map-use" -> routeMapUseRealisations(target, object, program);
      default -> List.of();
    };
  }

  /**
   * Gives the area of a level-1 router a way out by raising one of the area's level-1 routers to
   * level 1-2: the router itself first, then each of the others, since any of them would do for the
   * area and only verification tells which one the network needs.
   */
  private List<ProgramModel.Realisation> isisAttachmentRealisations(
      ProgramObject router, ProgramModel program) {
    List<String> area = router.values("isis-area");
    if (area.isEmpty() || router.value("isis-level").filter("level-1"::equals).isEmpty()) {
      return List.of();
    }
    List<ProgramObject> confined = new ArrayList<>(List.of(router));
    program.objects(ObjectType.ROUTER).stream()
        .filter(other -> !other.ref().equals(router.ref()))
        .filter(other -> other.values("isis-area").equals(area))
        .filter(other -> other.value("isis-level").filter("level-1"::equals).isPresent())
        .sorted(Comparator.comparing(other -> other.ref().toString()))
        .forEach(confined::add);
    Production level = dialect.grammar().production("isis-is-type").orElseThrow();
    return confined.stream()
        .map(
            other ->
                new ProgramModel.Realisation(
                    level, Map.of("level", List.of("level-1-2")), other.ref()))
        .toList();
  }

  /**
   * Relaxes a level-1-only IS-IS interface to the least disruptive dual-level configuration. This
   * is the constructive complement of the derived {@code isis-level-1} guard: level-1-2 preserves
   * level-1 reachability while making an inter-area level-2 adjacency possible.
   */
  private List<ProgramModel.Realisation> isisLevelOneRealisations(
      String target, ProgramObject object, ProgramModel program) {
    if (!target.equals("false") || !object.ref().type().equals("interface")) {
      return List.of();
    }
    List<ProgramModel.Realisation> result = new ArrayList<>();
    Production circuit = dialect.grammar().production("isis-circuit-type").orElseThrow();
    if (object.value("isis-circuit-type").filter("level-1"::equals).isPresent()) {
      result.add(new ProgramModel.Realisation(circuit, Map.of("level", List.of("level-1-2"))));
    }
    ProgramObject.Ref owner =
        new ProgramObject.Ref(ObjectType.ROUTER, object.ref().router(), List.of());
    program
        .object(owner)
        .filter(router -> router.value("isis-level").filter("level-1"::equals).isPresent())
        .ifPresent(
            router ->
                result.add(
                    new ProgramModel.Realisation(
                        dialect.grammar().production("isis-is-type").orElseThrow(),
                        Map.of("level", List.of("level-1-2")),
                        owner)));
    return List.copyOf(result);
  }

  /** Realises a router's loopback identity by changing Loopback0's address. */
  private List<ProgramModel.Realisation> loopbackRealisations(
      String target, ProgramObject router, ProgramModel program) {
    ProgramObject.Ref loopback =
        new ProgramObject.Ref("interface", router.ref().router(), List.of("Loopback0"));
    Optional<ProgramObject> object = program.object(loopback);
    if (object.isEmpty()) {
      return List.of();
    }
    Production address = dialect.grammar().production("ip-address").orElseThrow();
    return object.get().statements().stream()
        .filter(
            span ->
                program.statements(loopback.router()).stream()
                    .anyMatch(
                        statement ->
                            statement.span().equals(span)
                                && statement.match().stream()
                                    .anyMatch(match -> match.production().equals(address))))
        .findFirst()
        .map(
            span ->
                List.of(
                    new ProgramModel.Realisation(
                        address, Map.of("address", List.of(target)), loopback, span)))
        .orElseGet(List::of);
  }

  /**
   * Realises a definition-to-use edge for a route map. Redistribution is offered when the router
   * runs both protocols; neighbor attachments follow, with physical peers first. These are all
   * typed uses of the definition, and the decision model chooses among their concrete edits.
   */
  private List<ProgramModel.Realisation> routeMapUseRealisations(
      String target, ProgramObject routeMap, ProgramModel program) {
    if (!target.equals("true")) {
      return List.of();
    }
    RouterId router = routeMap.ref().router();
    String name = routeMap.ref().key().getFirst();
    ProgramObject owner =
        program.object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of())).orElseThrow();
    List<ProgramModel.Realisation> result = new ArrayList<>();
    if (owner.has("ospf-process") && owner.has("bgp-asn")) {
      Map<String, List<String>> values =
          Map.of("asn", owner.values("bgp-asn"), "map", List.of(name));
      result.add(
          new ProgramModel.Realisation(
              dialect.grammar().production("ospf-redistribute-bgp-metric").orElseThrow(),
              values,
              owner.ref()));
      result.add(
          new ProgramModel.Realisation(
              dialect.grammar().production("ospf-redistribute-bgp").orElseThrow(),
              values,
              owner.ref()));
    }
    Set<RouterId> physical = new HashSet<>(program.topology().neighbours(router));
    List<ProgramObject> neighbors =
        program.objectsOf(router).stream()
            .filter(candidate -> candidate.ref().type().equals("bgp-neighbor"))
            .sorted(
                java.util.Comparator.comparing(
                        (ProgramObject neighbor) ->
                            !directTransport(neighbor, router, physical, program))
                    .thenComparing(candidate -> candidate.ref().key().getFirst()))
            .toList();
    Production attachment = dialect.grammar().production("neighbor-route-map").orElseThrow();
    for (ProgramObject neighbor : neighbors) {
      for (String direction : List.of("in", "out")) {
        Optional<String> attached = neighbor.value("route-map-" + direction);
        String foreign = direction.equals("in") ? "export-attributes" : "import-attributes";
        if (attached.isPresent()
            && !routeMap.has(foreign)
            && broken(program, router, attached.get())) {
          attachmentStatement(program, neighbor, direction)
              .ifPresent(
                  statement ->
                      result.add(
                          0,
                          new ProgramModel.Realisation(
                              attachment,
                              Map.of(
                                  "peer", neighbor.ref().key(),
                                  "map", List.of(name),
                                  "direction", List.of(direction)),
                              neighbor.ref(),
                              statement)));
        }
        if (attached.isPresent()) {
          continue;
        }
        result.add(
            new ProgramModel.Realisation(
                attachment,
                Map.of(
                    "peer", neighbor.ref().key(),
                    "map", List.of(name),
                    "direction", List.of(direction)),
                neighbor.ref(),
                ProgramModel.Realisation.Existing.KEEP));
      }
    }
    return result;
  }

  /**
   * Returns whether the route map {@code name} on {@code router} sends routes to a next hop that no
   * router owns, which no session it is attached to can use.
   */
  private static boolean broken(ProgramModel program, RouterId router, String name) {
    String reference =
        ProgramObject.Ref.isReference(name, "route-map")
            ? ProgramObject.Ref.parse(name).key().getFirst()
            : name;
    return program.objectsOf(router).stream()
        .filter(object -> object.ref().type().equals("route-map-entry"))
        .filter(object -> object.ref().key().getFirst().equals(reference))
        .anyMatch(entry -> entry.has("next-hop") && !entry.has("next-hop-router"));
  }

  /**
   * Returns the span of the statement that attaches {@code neighbor}'s map in {@code direction}.
   */
  private static Optional<SourceSpan> attachmentStatement(
      ProgramModel program, ProgramObject neighbor, String direction) {
    Set<SourceSpan> spans = new HashSet<>(neighbor.statements());
    return program.statements(neighbor.ref().router()).stream()
        .filter(statement -> spans.contains(statement.span()))
        .filter(
            statement ->
                statement
                    .match()
                    .filter(match -> match.production().id().equals("neighbor-route-map"))
                    .filter(match -> match.values("direction").equals(List.of(direction)))
                    .isPresent())
        .map(Statement::span)
        .findFirst();
  }

  /** Returns whether a neighbor address is the address at the far end of a physical link. */
  private static boolean directTransport(
      ProgramObject neighbor, RouterId local, Set<RouterId> physical, ProgramModel program) {
    Optional<RouterId> peerRouter =
        neighbor
            .value("peer-router")
            .filter(value -> ProgramObject.Ref.isReference(value, ObjectType.ROUTER))
            .map(ProgramObject.Ref::parse)
            .map(ProgramObject.Ref::router)
            .filter(physical::contains);
    if (peerRouter.isEmpty()) {
      return false;
    }
    String peerAddress = neighbor.ref().key().getFirst();
    for (Topology.Link link : program.topology().links()) {
      Topology.Endpoint remote = null;
      if (link.a().router().equals(local) && link.b().router().equals(peerRouter.get())) {
        remote = link.b();
      } else if (link.b().router().equals(local) && link.a().router().equals(peerRouter.get())) {
        remote = link.a();
      }
      if (remote == null) {
        continue;
      }
      ProgramObject.Ref interfaceRef =
          new ProgramObject.Ref("interface", remote.router(), List.of(remote.interfaceName()));
      if (program.object(interfaceRef).flatMap(object -> object.value("address")).stream()
          .anyMatch(peerAddress::equals)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Realises an IGP on an interface: OSPF in the area the router's other interfaces use most, when
   * the router runs OSPF, and IS-IS when it runs IS-IS.
   */
  private List<ProgramModel.Realisation> igpRealisations(
      ProgramObject object, ProgramModel program) {
    ProgramObject router =
        program
            .object(new ProgramObject.Ref(ObjectType.ROUTER, object.ref().router(), List.of()))
            .orElseThrow();
    List<ProgramModel.Realisation> result = new ArrayList<>();
    router
        .value("ospf-process")
        .ifPresent(
            process -> {
              Map<String, Integer> areas = new HashMap<>();
              for (ProgramObject other : program.objectsOf(object.ref().router())) {
                if (other.ref().type().equals("interface")) {
                  other
                      .value("ospf-effective-area")
                      .ifPresent(a -> areas.merge(a, 1, Integer::sum));
                }
              }
              String area =
                  areas.entrySet().stream()
                      .max(Map.Entry.comparingByValue())
                      .map(Map.Entry::getKey)
                      .orElse("0");
              result.add(
                  new ProgramModel.Realisation(
                      dialect.grammar().production("ip-ospf-area").orElseThrow(),
                      Map.of("process", List.of(process), "area", List.of(area))));
            });
    router
        .value("isis-process")
        .ifPresent(
            process ->
                result.add(
                    new ProgramModel.Realisation(
                        dialect.grammar().production("ip-router-isis").orElseThrow(),
                        Map.of("process", List.of(process)))));
    return result;
  }

  /**
   * Takes an interface out of OSPF by narrowing each network statement that covers it to the
   * largest block of the statement's range that still covers the statement's other interfaces and
   * not this one. A statement that covers nothing else has no such block and is left to deletion.
   */
  private List<ProgramModel.Realisation> igpExclusions(ProgramObject object, ProgramModel program) {
    Optional<Ipv4> address = object.value("address").flatMap(Ipv4::tryParse);
    if (address.isEmpty()) {
      return List.of();
    }
    RouterId router = object.ref().router();
    List<Ipv4> others = new ArrayList<>();
    for (ProgramObject other : program.objectsOf(router)) {
      if (other.ref().type().equals("interface") && !other.ref().equals(object.ref())) {
        other.value("address").flatMap(Ipv4::tryParse).ifPresent(others::add);
      }
    }
    List<ProgramModel.Realisation> result = new ArrayList<>();
    ProgramObject.Ref owner = new ProgramObject.Ref(ObjectType.ROUTER, router, List.of());
    for (OspfNetwork network : ospfNetworks(program.statements(router))) {
      if (!network.prefix().contains(address.get())) {
        continue;
      }
      List<Ipv4> covered = others.stream().filter(network.prefix()::contains).toList();
      for (int length = network.prefix().length() + 1;
          length <= 32 && !covered.isEmpty();
          length++) {
        Ipv4 mask = Ipv4.mask(length);
        Ipv4 block = covered.getFirst().and(mask);
        if (covered.stream().allMatch(other -> other.and(mask).equals(block))
            && !address.get().and(mask).equals(block)) {
          result.add(
              new ProgramModel.Realisation(
                  dialect.grammar().production("ospf-network").orElseThrow(),
                  Map.of(
                      "network",
                      List.of(block.toString()),
                      "wildcard",
                      List.of(wildcard(length).toString())),
                  owner,
                  network.statement()));
          break;
        }
        if (!covered.stream().allMatch(other -> other.and(mask).equals(block))) {
          break;
        }
      }
    }
    return List.copyOf(result);
  }

  /** Returns the wildcard of a prefix of {@code length} bits. */
  private static Ipv4 wildcard(int length) {
    return new Ipv4(~Ipv4.mask(length).bits());
  }

  /**
   * Realises the coverage of an interface by an OSPF network statement: the statement keeps its
   * wildcard and moves to the interface's address.
   */
  private List<ProgramModel.Realisation> coverageRealisations(
      String target, ProgramObject object, ProgramModel program) {
    if (!ProgramObject.Ref.isReference(target, "interface")) {
      return List.of();
    }
    Optional<Ipv4> address =
        program
            .object(ProgramObject.Ref.parse(target))
            .flatMap(i -> i.value("address"))
            .flatMap(Ipv4::tryParse);
    Optional<Ipv4> wildcard = object.value("wildcard").flatMap(Ipv4::tryParse);
    if (address.isEmpty() || wildcard.isEmpty() || wildcard.get().wildcardLength().isEmpty()) {
      return List.of();
    }
    int length = wildcard.get().wildcardLength().getAsInt();
    return List.of(
        new ProgramModel.Realisation(
            dialect.grammar().production("ospf-network").orElseThrow(),
            Map.of("network", List.of(address.get().and(Ipv4.mask(length)).toString()))));
  }

  /**
   * Realises an interface's OSPF area by changing the area of the network statement that covers its
   * address most closely, when there is one, and by an interface-level area statement.
   */
  private List<ProgramModel.Realisation> areaRealisations(
      String target, ProgramObject object, ProgramModel program) {
    ProgramObject.Ref router =
        new ProgramObject.Ref(ObjectType.ROUTER, object.ref().router(), List.of());
    List<ProgramModel.Realisation> result = new ArrayList<>();
    Optional<Ipv4> address = object.value("address").flatMap(Ipv4::tryParse);
    if (address.isPresent()) {
      OspfNetwork best = null;
      for (OspfNetwork network : ospfNetworks(program.statements(object.ref().router()))) {
        if (network.prefix().contains(address.get())
            && (best == null || network.prefix().length() > best.prefix().length())) {
          best = network;
        }
      }
      if (best != null && !best.area().equals(target)) {
        result.add(
            new ProgramModel.Realisation(
                dialect.grammar().production("ospf-network").orElseThrow(),
                Map.of("area", List.of(target)),
                router,
                best.statement()));
      }
    }
    program
        .object(router)
        .flatMap(owner -> owner.value("ospf-process"))
        .ifPresent(
            process ->
                result.add(
                    new ProgramModel.Realisation(
                        dialect.grammar().production("ip-ospf-area").orElseThrow(),
                        Map.of("process", List.of(process), "area", List.of(target)))));
    return result;
  }

  /**
   * Realises a neighbor's remote AS on the peer group it inherits the AS from, when it inherits
   * one, and otherwise on the neighbor itself.
   */
  private List<ProgramModel.Realisation> remoteAsRealisations(
      String target, ProgramObject object, ProgramModel program) {
    if (!object.has("remote-as")) {
      Optional<ProgramObject> group =
          object
              .value("peer-group")
              .flatMap(
                  name ->
                      program.object(
                          new ProgramObject.Ref(
                              "peer-group", object.ref().router(), List.of(name))));
      if (group.isPresent() && group.get().has("remote-as")) {
        return List.of(
            new ProgramModel.Realisation(
                dialect.grammar().production("peer-group-remote-as").orElseThrow(),
                Map.of("group", group.get().ref().key(), "asn", List.of(target)),
                group.get().ref()));
      }
    }
    return List.of(
        new ProgramModel.Realisation(
            dialect.grammar().production("neighbor-remote-as").orElseThrow(),
            Map.of("peer", object.ref().key(), "asn", List.of(target))));
  }

  /**
   * Realises a subnet with the subnet's mask and the interface's own address when it lies in the
   * subnet, else a free address of it.
   */
  private List<ProgramModel.Realisation> subnetRealisations(
      String target, ProgramObject object, ProgramModel program) {
    Optional<Prefix> prefix = Prefix.tryParse(target);
    if (prefix.isEmpty()) {
      return List.of();
    }
    Set<Ipv4> used = new TreeSet<>();
    for (ProgramObject other : program.objects("interface")) {
      if (!other.ref().equals(object.ref())) {
        other.value("address").flatMap(Ipv4::tryParse).ifPresent(used::add);
      }
    }
    List<Ipv4> hosts = new ArrayList<>();
    object
        .value("address")
        .flatMap(Ipv4::tryParse)
        .filter(prefix.get()::contains)
        .ifPresent(hosts::add);
    for (Ipv4 host : hosts(prefix.get(), used)) {
      if (!hosts.contains(host)) {
        hosts.add(host);
      }
    }
    Production production = dialect.grammar().production("ip-address").orElseThrow();
    List<ProgramModel.Realisation> result = new ArrayList<>();
    for (Ipv4 host : hosts) {
      result.add(
          new ProgramModel.Realisation(
              production,
              Map.of(
                  "address", List.of(host.toString()),
                  "mask", List.of(prefix.get().mask().toString()))));
    }
    return result;
  }

  /** Returns up to a few unused host addresses of {@code prefix}, nearest to used ones first. */
  public static List<Ipv4> hosts(Prefix prefix, Set<Ipv4> used) {
    int size = prefix.length() >= Integer.SIZE - 1 ? 1 << (Integer.SIZE - prefix.length()) : 0;
    long first;
    long last;
    if (prefix.length() >= Integer.SIZE - 1) {
      first = Integer.toUnsignedLong(prefix.network().bits());
      last = first + size - 1;
    } else {
      first = Integer.toUnsignedLong(prefix.network().bits()) + 1;
      last =
          Integer.toUnsignedLong(prefix.network().bits())
              + (1L << (Integer.SIZE - prefix.length()))
              - 2;
    }
    List<Ipv4> candidates = new ArrayList<>();
    for (Ipv4 taken : used) {
      if (prefix.contains(taken)) {
        long bits = Integer.toUnsignedLong(taken.bits());
        for (long neighbour : new long[] {bits - 1, bits + 1}) {
          if (neighbour >= first && neighbour <= last) {
            candidates.add(new Ipv4((int) neighbour));
          }
        }
      }
    }
    for (long bits = first; bits <= last && candidates.size() < MAX_HOSTS * 2; bits++) {
      candidates.add(new Ipv4((int) bits));
    }
    List<Ipv4> result = new ArrayList<>();
    for (Ipv4 candidate : candidates) {
      if (!used.contains(candidate) && !result.contains(candidate)) {
        result.add(candidate);
        if (result.size() == MAX_HOSTS) {
          break;
        }
      }
    }
    return result;
  }

  /**
   * Realises a peer router by renaming the neighbor to an address that router owns: addresses of
   * the same kind as the current peer address first, loopbacks before link addresses.
   */
  private List<ProgramModel.Realisation> peerRealisations(
      String target, ProgramObject object, ProgramModel program) {
    if (!ProgramObject.Ref.isReference(target, ObjectType.ROUTER)) {
      return List.of();
    }
    RouterId router = ProgramObject.Ref.parse(target).router();
    List<String> loopbacks = new ArrayList<>();
    List<String> others = new ArrayList<>();
    for (ProgramObject candidate : program.objects("interface")) {
      if (!candidate.ref().router().equals(router) || !candidate.has("address")) {
        continue;
      }
      String name = candidate.ref().key().getFirst().toLowerCase(java.util.Locale.ROOT);
      (name.startsWith("loopback") ? loopbacks : others)
          .add(candidate.value("address").orElseThrow());
    }
    List<String> addresses = new ArrayList<>(loopbacks);
    addresses.addAll(others);
    List<ProgramModel.Realisation> result = new ArrayList<>();
    ObjectType type = dialect.schema().object(object.ref().type()).orElseThrow();
    if (object.has("next-hop")) {
      Production production = dialect.grammar().production("set-next-hop").orElseThrow();
      for (String address : addresses) {
        result.add(new ProgramModel.Realisation(production, Map.of("next-hop", List.of(address))));
      }
      return result;
    }
    for (String address : addresses) {
      for (Production source : type.sources()) {
        if (source.id().equals("neighbor-remote-as")) {
          result.add(new ProgramModel.Realisation(source, Map.of("peer", List.of(address))));
        }
      }
    }
    return result;
  }
}
