package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.HashMap;
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
 * a network or route statement, the AS a router's name assigns it, and a few more. It also knows
 * how to realise a wanted subnet, area, remote AS, peer router, coverage or prefix as text.
 */
public final class CiscoIosSemantics implements ProgramModel.Factory {
  private static final Set<String> FUNCTIONS =
      Set.of(
          "address-owner",
          "design-asn",
          "effective-remote-as",
          "igp-enabled",
          "igp-peer",
          "isis-area",
          "isis-level-1",
          "link-peer",
          "loopback-address",
          "ospf-coverage",
          "network-prefix",
          "ospf-area",
          "subnet");
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
    return derived;
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
      case "network-prefix" -> prefixOf(object.value("network"), object.value("mask"));
      case "link-peer" ->
          topology
              .peer(new Topology.Endpoint(router, object.ref().key().getFirst()))
              .map(
                  end ->
                      new ProgramObject.Ref("interface", end.router(), List.of(end.interfaceName()))
                          .toString())
              .map(List::of)
              .orElse(List.of());
      case "igp-peer" ->
          topology
              .peer(new Topology.Endpoint(router, object.ref().key().getFirst()))
              .filter(end -> designAsn(end.router()).equals(designAsn(router)))
              .map(
                  end ->
                      new ProgramObject.Ref("interface", end.router(), List.of(end.interfaceName()))
                          .toString())
              .map(List::of)
              .orElse(List.of());
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
        ProgramObject lowest = null;
        for (ProgramObject candidate : byRef.values()) {
          if (candidate.ref().type().equals("interface")
              && candidate.ref().key().getFirst().startsWith("Loopback")
              && candidate.has("address")
              && (lowest == null
                  || candidate.ref().key().getFirst().compareTo(lowest.ref().key().getFirst())
                      < 0)) {
            lowest = candidate;
          }
        }
        yield lowest == null ? List.of() : lowest.values("address");
      }
      case "ospf-coverage" -> {
        Optional<Prefix> prefix = wildcardPrefix(object.value("network"), object.value("wildcard"));
        if (prefix.isEmpty()) {
          yield List.of();
        }
        for (ProgramObject candidate : byRef.values()) {
          if (candidate.ref().type().equals("interface")
              && candidate
                  .value("address")
                  .flatMap(Ipv4::tryParse)
                  .filter(prefix.get()::contains)
                  .isPresent()) {
            yield List.of(candidate.ref().toString());
          }
        }
        yield List.of();
      }
      default -> throw new IllegalArgumentException("unknown derived function " + function);
    };
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
      case "ospf-coverage" -> coverageRealisations(target, object, program);
      case "igp-enabled" -> igpRealisations(object, program);
      default -> List.of();
    };
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
