package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.text.RouterId;

/**
 * A finite, typed route-reflector/client incidence relation inferred from a configuration program.
 *
 * <p>An explicit BGP cluster identity gives the reflector role. Same-AS routers with owned loopback
 * identities give the client domain, including a router whose design AS is known but whose BGP
 * process is absent, and excluding a router whose BGP process names another AS than its design: its
 * membership is itself in doubt, so it is not wired into either system. Existing sessions give the
 * directional peer-group types and whether that type uses next-hop-self. The relation therefore
 * also represents pairs for which both session directions are absent; it does not infer names or
 * addresses from benchmark labels.
 *
 * @param reflector the router object with an explicit cluster identity
 * @param clients the client router objects whose incidence with the reflector is incomplete
 * @param directions the incomplete directions of those incidences
 */
public record BgpIncidenceSet(
    ProgramObject.Ref reflector, List<ProgramObject.Ref> clients, List<Direction> directions) {

  /** One observed peer-group type that can realise a direction. */
  public record Group(String name, ProgramObject.Ref template, boolean nextHopSelf) {
    /** Checks the group. */
    public Group {
      if (name.isEmpty()) {
        throw new IllegalArgumentException("a peer-group name is not empty");
      }
      java.util.Objects.requireNonNull(template, "template");
    }
  }

  /**
   * One incomplete directed session.
   *
   * @param source the router on which the neighbor object belongs
   * @param target the router whose loopback is the neighbor key
   * @param peer the target's loopback address
   * @param groups the observed directional types that can realise the session
   */
  public record Direction(
      ProgramObject.Ref source, ProgramObject.Ref target, String peer, List<Group> groups) {
    /** Copies the groups and checks the values. */
    public Direction {
      java.util.Objects.requireNonNull(source, "source");
      java.util.Objects.requireNonNull(target, "target");
      if (peer.isEmpty()) {
        throw new IllegalArgumentException("a peer address is not empty");
      }
      groups = List.copyOf(groups);
      if (groups.isEmpty()) {
        throw new IllegalArgumentException("a direction needs an observed peer-group type");
      }
    }
  }

  /** Copies the members and checks that the set contains an incomplete incidence. */
  public BgpIncidenceSet {
    java.util.Objects.requireNonNull(reflector, "reflector");
    clients = List.copyOf(clients);
    directions = List.copyOf(directions);
    if (clients.isEmpty() || directions.isEmpty()) {
      throw new IllegalArgumentException("an incidence set needs an incomplete member");
    }
  }

  /** Returns a stable identifier for this incidence set. */
  public ContentHash id() {
    ContentHash.Builder digest = ContentHash.builder().add("bgp-incidence-set");
    digest.add(reflector.toString());
    clients.forEach(client -> digest.add(client.toString()));
    return digest.build();
  }

  /** Returns every router object participating in the set. */
  public Set<ProgramObject.Ref> objects() {
    Set<ProgramObject.Ref> objects = new LinkedHashSet<>();
    objects.add(reflector);
    objects.addAll(clients);
    return Set.copyOf(objects);
  }

  /** Returns every router whose configuration a completion can change. */
  public NavigableSet<RouterId> routers() {
    NavigableSet<RouterId> routers = new TreeSet<>();
    objects().forEach(object -> routers.add(object.router()));
    return Collections.unmodifiableNavigableSet(routers);
  }

  /**
   * Infers incomplete incidence sets relevant to {@code relevant} routers.
   *
   * <p>At least two incomplete client incidences are normally required; a single missing direction
   * is already represented by the ordinary {@code bgp-session} relation. A single incidence is
   * retained when both directions are absent, because then no existing neighbor can instantiate
   * that ordinary relation. A single processless client is retained for the same reason: its
   * process definition and both session directions form one conjunctive object. A client is
   * retained only when formal verifier evidence implicates it.
   */
  public static List<BgpIncidenceSet> infer(ProgramModel program, Collection<RouterId> relevant) {
    return infer(program, relevant, Set.of());
  }

  /**
   * Infers incomplete incidence sets, allowing a processless client only when a formal obligation
   * in {@code processRequired} names that router as a required BGP incidence endpoint.
   */
  public static List<BgpIncidenceSet> infer(
      ProgramModel program, Collection<RouterId> relevant, Collection<RouterId> processRequired) {
    Set<RouterId> implicated = Set.copyOf(relevant);
    Set<RouterId> requiredProcesses = Set.copyOf(processRequired);
    Map<RouterId, ProgramObject> routers = routers(program);
    Map<String, List<ProgramObject>> byAs = new LinkedHashMap<>();
    for (ProgramObject router : routers.values()) {
      Optional<String> configured = router.value("bgp-asn");
      Optional<String> designed = router.value("design-asn");
      if (configured.isPresent() && designed.isPresent() && !configured.equals(designed)) {
        continue;
      }
      configured
          .or(() -> designed)
          .ifPresent(asn -> byAs.computeIfAbsent(asn, ignored -> new ArrayList<>()).add(router));
    }
    List<BgpIncidenceSet> result = new ArrayList<>();
    for (List<ProgramObject> members : byAs.values()) {
      List<ProgramObject> reflectors =
          members.stream().filter(router -> router.has("bgp-cluster-id")).toList();
      List<ProgramObject> clients =
          members.stream()
              .filter(
                  router ->
                      !router.has("bgp-cluster-id")
                          && router.has("loopback-address")
                          && (router.has("bgp-asn")
                              || requiredProcesses.contains(router.ref().router())))
              .toList();
      for (ProgramObject reflector : reflectors) {
        if (!reflector.has("loopback-address")) {
          continue;
        }
        List<ProgramObject.Ref> incompleteClients = new ArrayList<>();
        List<Direction> incompleteDirections = new ArrayList<>();
        boolean whollyMissing = false;
        for (ProgramObject client : clients) {
          if (!implicated.contains(client.ref().router())) {
            continue;
          }
          List<Direction> pair = new ArrayList<>();
          direction(program, routers, reflector, client).ifPresent(pair::add);
          direction(program, routers, client, reflector).ifPresent(pair::add);
          if (!pair.isEmpty()) {
            incompleteClients.add(client.ref());
            incompleteDirections.addAll(pair);
            whollyMissing |= pair.size() == 2 && absent(program, reflector, client);
          }
        }
        boolean missingProcess =
            incompleteClients.stream()
                .map(client -> routers.get(client.router()))
                .anyMatch(client -> client != null && client.value("bgp-asn").isEmpty());
        if (!incompleteDirections.isEmpty()
            && (incompleteClients.size() >= 2 || missingProcess || whollyMissing)) {
          result.add(new BgpIncidenceSet(reflector.ref(), incompleteClients, incompleteDirections));
        }
      }
    }
    return List.copyOf(result);
  }

  /**
   * Returns the incidence between {@code first} and {@code second} when one is a reflector with an
   * explicit cluster identity and the other a client with a loopback in its autonomous system, and
   * a direction between them is incomplete. It completes a single session by the peer-group types
   * the routers already use, which a bare session completion does not, so it is offered alongside
   * the ordinary {@code bgp-session} relation rather than inferred as a set of its own.
   */
  public static Optional<BgpIncidenceSet> pair(
      ProgramModel program, RouterId first, RouterId second) {
    Map<RouterId, ProgramObject> routers = routers(program);
    for (List<RouterId> order : List.of(List.of(first, second), List.of(second, first))) {
      ProgramObject reflector = routers.get(order.get(0));
      ProgramObject client = routers.get(order.get(1));
      if (reflector == null
          || client == null
          || !reflector.has("bgp-cluster-id")
          || client.has("bgp-cluster-id")
          || !reflector.has("loopback-address")
          || !client.has("loopback-address")
          || reflector.value("bgp-asn").isEmpty()
          || !reflector.value("bgp-asn").equals(client.value("bgp-asn"))) {
        continue;
      }
      List<Direction> directions = new ArrayList<>();
      direction(program, routers, reflector, client).ifPresent(directions::add);
      direction(program, routers, client, reflector).ifPresent(directions::add);
      if (!directions.isEmpty()) {
        return Optional.of(new BgpIncidenceSet(reflector.ref(), List.of(client.ref()), directions));
      }
    }
    return Optional.empty();
  }

  /** Returns whether neither router has a neighbor object naming the other's loopback. */
  private static boolean absent(ProgramModel program, ProgramObject first, ProgramObject second) {
    String firstPeer = second.value("loopback-address").orElse("");
    String secondPeer = first.value("loopback-address").orElse("");
    return !firstPeer.isEmpty()
        && !secondPeer.isEmpty()
        && program
            .object(new ProgramObject.Ref("bgp-neighbor", first.ref().router(), List.of(firstPeer)))
            .isEmpty()
        && program
            .object(
                new ProgramObject.Ref("bgp-neighbor", second.ref().router(), List.of(secondPeer)))
            .isEmpty();
  }

  private static Optional<Direction> direction(
      ProgramModel program,
      Map<RouterId, ProgramObject> routers,
      ProgramObject source,
      ProgramObject target) {
    String peer = target.value("loopback-address").orElse("");
    if (peer.isEmpty()) {
      return Optional.empty();
    }
    boolean targetReflector = target.has("bgp-cluster-id");
    List<Group> groups = groups(program, routers, source, targetReflector);
    if (groups.isEmpty()) {
      return Optional.empty();
    }
    ProgramObject neighbor =
        program
            .object(new ProgramObject.Ref("bgp-neighbor", source.ref().router(), List.of(peer)))
            .orElse(null);
    if (neighbor != null && complete(neighbor, groups)) {
      return Optional.empty();
    }
    return Optional.of(new Direction(source.ref(), target.ref(), peer, groups));
  }

  private static boolean complete(ProgramObject neighbor, List<Group> groups) {
    String assigned =
        neighbor.value("peer-group").flatMap(BgpIncidenceSet::referenceKey).orElse("");
    return neighbor.has("activated")
        && !neighbor.has("shutdown")
        && groups.stream()
            .anyMatch(
                group ->
                    group.name().equals(assigned)
                        && (!group.nextHopSelf() || neighbor.has("next-hop-self")));
  }

  /** Returns peer groups observed locally toward routers of {@code targetReflector}'s role. */
  private static List<Group> groups(
      ProgramModel program,
      Map<RouterId, ProgramObject> routers,
      ProgramObject source,
      boolean targetReflector) {
    List<Group> local = observedGroups(program, routers, source, targetReflector);
    if (!local.isEmpty()) {
      return local;
    }
    String sourceAs = source.value("bgp-asn").or(() -> source.value("design-asn")).orElse("");
    if (sourceAs.isEmpty()) {
      return List.of();
    }
    Map<String, List<ProgramObject>> inherited = new LinkedHashMap<>();
    for (ProgramObject neighbor : program.objects("bgp-neighbor")) {
      ProgramObject localRouter = routers.get(neighbor.ref().router());
      ProgramObject target =
          neighbor
              .value("peer-router")
              .flatMap(BgpIncidenceSet::reference)
              .map(ref -> routers.get(ref.router()))
              .orElse(null);
      String group = neighbor.value("peer-group").flatMap(BgpIncidenceSet::referenceKey).orElse("");
      if (localRouter == null
          || target == null
          || group.isEmpty()
          || !localRouter
              .value("bgp-asn")
              .or(() -> localRouter.value("design-asn"))
              .filter(sourceAs::equals)
              .isPresent()
          || target.has("bgp-cluster-id") != targetReflector
          || !target
              .value("bgp-asn")
              .or(() -> target.value("design-asn"))
              .filter(sourceAs::equals)
              .isPresent()) {
        continue;
      }
      inherited.computeIfAbsent(group, ignored -> new ArrayList<>()).add(neighbor);
    }
    return groupSummaries(inherited, Optional.of(source.ref()));
  }

  private static List<Group> observedGroups(
      ProgramModel program,
      Map<RouterId, ProgramObject> routers,
      ProgramObject source,
      boolean targetReflector) {
    Map<String, List<ProgramObject>> byGroup = new LinkedHashMap<>();
    for (ProgramObject neighbor : program.objects("bgp-neighbor")) {
      if (!neighbor.ref().router().equals(source.ref().router())) {
        continue;
      }
      ProgramObject target =
          neighbor
              .value("peer-router")
              .flatMap(BgpIncidenceSet::reference)
              .map(ref -> routers.get(ref.router()))
              .orElse(null);
      String group = neighbor.value("peer-group").flatMap(BgpIncidenceSet::referenceKey).orElse("");
      if (target == null
          || group.isEmpty()
          || target.has("bgp-cluster-id") != targetReflector
          || !target.value("bgp-asn").equals(source.value("bgp-asn"))) {
        continue;
      }
      byGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(neighbor);
    }
    return groupSummaries(byGroup, Optional.empty());
  }

  private static List<Group> groupSummaries(
      Map<String, List<ProgramObject>> byGroup, Optional<ProgramObject.Ref> template) {
    List<Group> result = new ArrayList<>();
    byGroup.forEach(
        (name, observations) -> {
          int nextHopSelf = (int) observations.stream().filter(n -> n.has("next-hop-self")).count();
          result.add(
              new Group(
                  name,
                  template.orElseGet(() -> observations.getFirst().ref()),
                  nextHopSelf * 2 > observations.size()));
        });
    return List.copyOf(result);
  }

  private static Map<RouterId, ProgramObject> routers(ProgramModel program) {
    Map<RouterId, ProgramObject> routers = new HashMap<>();
    for (ProgramObject router : program.objects("router")) {
      routers.put(router.ref().router(), router);
    }
    return routers;
  }

  private static Optional<ProgramObject.Ref> reference(String text) {
    try {
      return Optional.of(ProgramObject.Ref.parse(text));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  private static Optional<String> referenceKey(String text) {
    if (!text.contains(":")) {
      return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
    return reference(text).filter(ref -> ref.key().size() == 1).map(ref -> ref.key().getFirst());
  }
}
