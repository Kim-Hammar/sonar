package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.Verification;

/**
 * Extends an identity rename through the program's definition-use graph.
 *
 * <p>When two loopbacks have the same address, their BGP neighbor objects collapse onto one key. A
 * candidate that gives one loopback its declared router identity must therefore split the uses:
 * local protocol identities follow the definition and each remote router's references form one
 * indivisible use group. Because the program cannot distinguish which of several remote use groups
 * denotes the renamed definition, the search space contains one transaction per possible group as
 * well as the whole-object transaction. All edits are derived from typed statements; no address or
 * router is named by this rule.
 */
public final class IdentityRefactoring {
  private IdentityRefactoring() {}

  /**
   * Returns the direct loopback-address seed implied by agreeing local protocol identities.
   *
   * <p>This is deliberately scoped to identity refactoring rather than added to the global address
   * domain: an OSPF or BGP router ID is evidence for Loopback0, but not for every physical
   * interface on the router.
   */
  public static List<List<EditOperator>> consensusSeeds(
      ProgramModel program, ProgramObject.Ref site) {
    ProgramObject loopback = program.object(site).orElse(null);
    ProgramObject router =
        program
            .object(new ProgramObject.Ref(ObjectType.ROUTER, site.router(), List.of()))
            .orElse(null);
    if (loopback == null
        || router == null
        || !site.type().equals("interface")
        || !site.key().equals(List.of("Loopback0"))) {
      return List.of();
    }
    Optional<String> structural = isisIdentity(program, router.ref().router());
    Set<String> declared = new HashSet<>();
    declared.addAll(router.values("ospf-router-id"));
    declared.addAll(router.values("bgp-router-id"));
    declared.removeAll(loopback.values("address"));
    String identity;
    Provenance provenance;
    if (structural.isPresent()) {
      identity = structural.orElseThrow();
      provenance = new Provenance.Inferred("leave-one-out IS-IS system identity agreement");
    } else if (declared.size() == 1) {
      identity = declared.iterator().next();
      provenance = new Provenance.Inferred("local protocol identity agreement");
    } else {
      return List.of();
    }
    for (var span : loopback.statements()) {
      Statement statement =
          program.statements(site.router()).stream()
              .filter(candidate -> candidate.span().equals(span))
              .findFirst()
              .orElse(null);
      if (statement == null) {
        continue;
      }
      Production.Match match = statement.match().orElse(null);
      if (match != null && match.production().id().equals("ip-address")) {
        return List.of(
            List.of(
                new EditOperator.ReplaceSlots(
                    statement, "address", List.of(identity), provenance)));
      }
    }
    return List.of();
  }

  /**
   * Returns the loopback identity forced by an unowned formal host endpoint.
   *
   * <p>A duplicated Loopback0 cannot uniquely denote a router. If the unresolved positive waypoint
   * obligations name exactly one unowned /32 through that router, the ownership bijection forces
   * that host address onto the loopback. Without such a waypoint, if the unresolved obligations to
   * deliver to a host require exactly one address that no interface owns, that address is the one
   * the duplicate displaced. Both the address and host mask are restored; the dependency closure
   * subsequently updates every exact use.
   */
  public static List<List<EditOperator>> verificationSeeds(
      ProgramModel program, Verification verification, ProgramObject.Ref site) {
    ProgramObject loopback = program.object(site).orElse(null);
    if (loopback == null
        || !site.type().equals("interface")
        || !site.key().equals(List.of("Loopback0"))
        || loopback.value("address").isEmpty()) {
      return List.of();
    }
    String before = loopback.value("address").orElseThrow();
    long owners =
        program.objects("interface").stream()
            .filter(object -> object.values("address").contains(before))
            .map(ProgramObject::ref)
            .distinct()
            .count();
    Optional<String> identity =
        requiredUnownedHostIdentity(program, verification, site.router())
            .or(() -> uniqueUnownedHostDestination(program, verification));
    if (owners < 2 || identity.isEmpty()) {
      return List.of();
    }
    for (var span : loopback.statements()) {
      Statement statement =
          program.statements(site.router()).stream()
              .filter(candidate -> candidate.span().equals(span))
              .findFirst()
              .orElse(null);
      Production.Match match = statement == null ? null : statement.match().orElse(null);
      if (match != null && match.production().id().equals("ip-address")) {
        return List.of(
            List.of(
                new EditOperator.ReplaceSlots(
                    statement,
                    Map.of(
                        "address", List.of(identity.orElseThrow()),
                        "mask", List.of("255.255.255.255")),
                    new Provenance.Inferred("formal host-ownership bijection"))));
      }
    }
    return List.of();
  }

  /**
   * Learns an exact loopback/IS-IS-system-ID relation from every other router in the snapshot.
   *
   * <p>The low system-ID component is not assumed to encode an address. It is used only when all
   * other routers carrying both values agree with that interpretation, share one loopback /24, and
   * the implied address is currently unowned. This is a leave-one-out functional invariant, so a
   * single corrupted identity is recovered without consulting hostnames or benchmark labels.
   */
  private static Optional<String> isisIdentity(ProgramModel program, RouterId target) {
    Set<String> prefixes = new HashSet<>();
    int observations = 0;
    for (ProgramObject router : program.objects(ObjectType.ROUTER)) {
      if (router.ref().router().equals(target) || !router.has("isis-net")) {
        continue;
      }
      Optional<AddressIdentity> identity = addressIdentity(router);
      if (identity.isEmpty() || identity.get().host() != identity.get().system()) {
        return Optional.empty();
      }
      prefixes.add(identity.get().prefix());
      observations++;
    }
    ProgramObject router =
        program.object(new ProgramObject.Ref(ObjectType.ROUTER, target, List.of())).orElse(null);
    if (router == null || observations < 2 || prefixes.size() != 1) {
      return Optional.empty();
    }
    int system = systemId(router.value("isis-net").orElse(""));
    if (system < 1 || system > 254) {
      return Optional.empty();
    }
    String candidate = prefixes.iterator().next() + system;
    boolean owned =
        program.objects("interface").stream()
            .filter(object -> !object.ref().router().equals(target))
            .anyMatch(object -> object.values("address").contains(candidate));
    return owned ? Optional.empty() : Optional.of(candidate);
  }

  private record AddressIdentity(String prefix, int host, int system) {}

  private static Optional<AddressIdentity> addressIdentity(ProgramObject router) {
    String address = router.value("loopback-address").orElse("");
    int separator = address.lastIndexOf('.');
    int system = systemId(router.value("isis-net").orElse(""));
    if (separator < 0 || system < 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          new AddressIdentity(
              address.substring(0, separator + 1),
              Integer.parseInt(address.substring(separator + 1)),
              system));
    } catch (NumberFormatException malformed) {
      return Optional.empty();
    }
  }

  private static int systemId(String net) {
    int nsel = net.lastIndexOf('.');
    int separator = nsel < 0 ? -1 : net.lastIndexOf('.', nsel - 1);
    if (separator < 0 || nsel == net.length() - 1) {
      return -1;
    }
    String component = net.substring(separator + 1, nsel);
    if (!component.matches("[0-9A-Fa-f]{4}")) {
      return -1;
    }
    try {
      return Integer.parseInt(component, 16);
    } catch (NumberFormatException malformed) {
      return -1;
    }
  }

  /** Returns dependency-closed variants of hypotheses that rename a colliding loopback address. */
  public static List<Hypothesis> complete(
      ProgramModel program, List<? extends Hypothesis> hypotheses) {
    return complete(program, null, hypotheses);
  }

  /**
   * Returns dependency-closed variants while preserving formally required host ownership.
   *
   * <p>If a duplicated host address is already present on the waypoint named by a failing /32
   * specification, moving that address off the waypoint cannot satisfy the obligation. This breaks
   * the otherwise symmetric choice of which colliding definition to rename without relying on
   * router names or an addressing convention.
   */
  public static List<Hypothesis> complete(
      ProgramModel program, Verification verification, List<? extends Hypothesis> hypotheses) {
    Set<Endpoint> protectedEndpoints =
        verification == null ? Set.of() : protectedEndpoints(program, verification);
    List<Hypothesis> completed = new ArrayList<>();
    for (Hypothesis hypothesis : hypotheses) {
      identityChange(program, hypothesis)
          .filter(
              change ->
                  !protectedEndpoints.contains(new Endpoint(change.router(), change.before())))
          .filter(change -> agreesWithDeclaredIdentity(program, change))
          .ifPresent(change -> completed.addAll(complete(program, hypothesis, change)));
    }
    Map<ContentHash, Hypothesis> distinct = new LinkedHashMap<>();
    completed.forEach(hypothesis -> distinct.putIfAbsent(hypothesis.id(), hypothesis));
    return List.copyOf(distinct.values());
  }

  /**
   * Returns all hypotheses, replacing colliding loopback renames with their dependency-closed
   * variants and retaining hypotheses unrelated to identity refactoring.
   */
  public static List<Hypothesis> closeAll(
      ProgramModel program, Verification verification, List<? extends Hypothesis> hypotheses) {
    List<Hypothesis> result = new ArrayList<>();
    for (Hypothesis hypothesis : hypotheses) {
      if (identityChange(program, hypothesis).isEmpty()) {
        result.add(hypothesis);
      }
    }
    result.addAll(complete(program, verification, hypotheses));
    return List.copyOf(result);
  }

  private record Change(RouterId router, String before, String after, Provenance provenance) {}

  private record Endpoint(RouterId router, String address) {}

  private static boolean agreesWithDeclaredIdentity(ProgramModel program, Change change) {
    ProgramObject router =
        program
            .object(new ProgramObject.Ref(ObjectType.ROUTER, change.router(), List.of()))
            .orElse(null);
    if (router == null) {
      return true;
    }
    Set<String> declared = new HashSet<>();
    declared.addAll(router.values("ospf-router-id"));
    declared.addAll(router.values("bgp-router-id"));
    declared.remove(change.before());
    return declared.size() != 1 || declared.contains(change.after());
  }

  private static Set<Endpoint> protectedEndpoints(ProgramModel program, Verification verification) {
    Set<Endpoint> result = new HashSet<>();
    List<Specification> failures = new ArrayList<>(verification.verdict().unresolved());
    failures.addAll(verification.verdict().sideEffects());
    for (Specification specification : failures) {
      if (!specification.status().expectPresent()
          || !(specification.predicate() instanceof ForwardingPredicate.Waypointing waypoint)
          || waypoint.destination().length() != 32) {
        continue;
      }
      String address = waypoint.destination().network().toString();
      boolean localHost =
          program.objectsOf(waypoint.waypoint()).stream()
              .filter(object -> object.ref().type().equals("interface"))
              .filter(object -> object.values("address").contains(address))
              .flatMap(object -> object.values("subnet").stream())
              .map(Prefix::tryParse)
              .flatMap(Optional::stream)
              .anyMatch(prefix -> prefix.length() == 32);
      long owners =
          program.objects("interface").stream()
              .filter(object -> object.values("address").contains(address))
              .map(ProgramObject::ref)
              .distinct()
              .count();
      if (localHost && owners > 1) {
        if (requiredUnownedHostIdentity(program, verification, waypoint.waypoint()).isEmpty()) {
          result.add(new Endpoint(waypoint.waypoint(), address));
        }
      }
    }
    return Set.copyOf(result);
  }

  /**
   * Returns the unique unowned positive /32 endpoint required through {@code router} that the
   * router can own. A router that must itself reach the endpoint through another router lies on the
   * way to the endpoint's owner and is not that owner.
   */
  private static Optional<String> requiredUnownedHostIdentity(
      ProgramModel program, Verification verification, RouterId router) {
    Set<String> owned = new HashSet<>();
    for (ProgramObject object : program.objects("interface")) {
      owned.addAll(object.values("address"));
    }
    Set<String> required = new HashSet<>();
    Set<String> reachedThroughAnother = new HashSet<>();
    List<Specification> failures = new ArrayList<>(verification.verdict().unresolved());
    failures.addAll(verification.verdict().sideEffects());
    for (Specification specification : failures) {
      if (!specification.status().expectPresent()
          || !(specification.predicate() instanceof ForwardingPredicate.Waypointing waypoint)
          || waypoint.destination().length() != 32) {
        continue;
      }
      String address = waypoint.destination().network().toString();
      if (waypoint.waypoint().equals(router)) {
        if (!owned.contains(address)) {
          required.add(address);
        }
      } else if (waypoint.source().equals(router)) {
        reachedThroughAnother.add(address);
      }
    }
    required.removeAll(reachedThroughAnother);
    return required.size() == 1 ? Optional.of(required.iterator().next()) : Optional.empty();
  }

  /**
   * Returns the unique /32 destination that unresolved obligations require delivered to and that no
   * interface owns, whatever the predicate.
   */
  private static Optional<String> uniqueUnownedHostDestination(
      ProgramModel program, Verification verification) {
    Set<String> owned = new HashSet<>();
    for (ProgramObject object : program.objects("interface")) {
      owned.addAll(object.values("address"));
    }
    Set<String> required = new HashSet<>();
    List<Specification> failures = new ArrayList<>(verification.verdict().unresolved());
    failures.addAll(verification.verdict().sideEffects());
    for (Specification specification : failures) {
      if (!specification.requiresDelivery()
          || specification.predicate().destination().length() != 32) {
        continue;
      }
      String address = specification.predicate().destination().network().toString();
      if (owned.contains(address)) {
        continue;
      }
      if (specification.predicate() instanceof ForwardingPredicate.Waypointing) {
        // A waypoint names the owner, or that a router is not it; that evidence decides alone
        return Optional.empty();
      }
      required.add(address);
    }
    return required.size() == 1 ? Optional.of(required.iterator().next()) : Optional.empty();
  }

  private static Optional<Change> identityChange(ProgramModel program, Hypothesis hypothesis) {
    for (EditOperator operator : hypothesis.operators()) {
      if (!(operator instanceof EditOperator.ReplaceSlots replace)
          || !replace.statement().match().orElseThrow().production().id().equals("ip-address")) {
        continue;
      }
      List<String> after = replace.values().get("address");
      List<String> before = replace.statement().match().orElseThrow().values("address");
      if (after == null || after.size() != 1 || before.size() != 1) {
        continue;
      }
      boolean loopback =
          program.objects("interface").stream()
              .filter(object -> object.ref().router().equals(operator.router()))
              .filter(object -> object.ref().key().equals(List.of("Loopback0")))
              .anyMatch(object -> object.statements().contains(replace.statement().span()));
      long owners =
          program.objects("interface").stream()
              .filter(object -> object.values("address").equals(before))
              .count();
      if (loopback && owners > 1 && !before.equals(after)) {
        return Optional.of(
            new Change(
                operator.router(), before.getFirst(), after.getFirst(), operator.provenance()));
      }
    }
    return Optional.empty();
  }

  private static List<Hypothesis> complete(
      ProgramModel program, Hypothesis hypothesis, Change change) {
    List<EditOperator> local = new ArrayList<>(hypothesis.operators());
    localReferences(program, change, local);
    List<List<EditOperator>> remoteGroups = neighborReferenceGroups(program, change);
    List<Hypothesis> variants = new ArrayList<>();
    addVariant(program, hypothesis, change, local, variants);
    for (List<EditOperator> remote : remoteGroups) {
      List<EditOperator> operators = new ArrayList<>(local);
      operators.addAll(remote);
      addVariant(program, hypothesis, change, operators, variants);
    }
    if (remoteGroups.size() > 1) {
      List<EditOperator> all = new ArrayList<>(local);
      remoteGroups.forEach(all::addAll);
      addVariant(program, hypothesis, change, all, variants);
    }
    return List.copyOf(variants);
  }

  /** Adds one consistent assignment of the renamed definition's uses. */
  private static void addVariant(
      ProgramModel program,
      Hypothesis original,
      Change change,
      List<EditOperator> operators,
      List<Hypothesis> variants) {
    if (operators.size() == original.operators().size()) {
      return;
    }
    List<EditOperator> normalized = new ArrayList<>(operators);
    removeRedundantRemoteAs(program, change, normalized);
    try {
      variants.add(Hypothesis.of(program.snapshot(), normalized));
    } catch (IllegalArgumentException conflict) {
      return;
    }
  }

  /** Keeps protocol identifiers and exact host coverage tied to the renamed local definition. */
  private static void localReferences(
      ProgramModel program, Change change, List<EditOperator> operators) {
    Set<String> identityProductions = Set.of("bgp-router-id", "ospf-router-id", "ospf-network");
    for (Statement statement : program.statements(change.router())) {
      Production.Match match = statement.match().orElse(null);
      if (match == null || !identityProductions.contains(match.production().id())) {
        continue;
      }
      for (String slot : List.of("id", "network")) {
        if (match.production().slot(slot).isPresent()
            && match.values(slot).equals(List.of(change.before()))
            && (slot.equals("id") || match.values("wildcard").equals(List.of("0.0.0.0")))) {
          operators.add(
              new EditOperator.ReplaceSlots(
                  statement, slot, List.of(change.after()), change.provenance()));
        }
      }
    }
  }

  /** Returns each remote router's indivisible uses of a collapsed BGP neighbor definition. */
  private static List<List<EditOperator>> neighborReferenceGroups(
      ProgramModel program, Change change) {
    String designAs =
        program
            .object(new ProgramObject.Ref(ObjectType.ROUTER, change.router(), List.of()))
            .flatMap(router -> router.value("design-asn"))
            .orElse("");
    List<RouterId> owners =
        program.objects("interface").stream()
            .filter(object -> object.values("address").equals(List.of(change.before())))
            .map(object -> object.ref().router())
            .filter(router -> sameDesignAs(program, router, designAs))
            .distinct()
            .toList();
    if (owners.isEmpty()) {
      return List.of();
    }
    List<List<EditOperator>> alternatives = new ArrayList<>();
    Optional<String> reciprocalGroup = reciprocalPeerGroup(program, change, owners);
    ObjectType neighborType = program.schema().object("bgp-neighbor").orElseThrow();
    Set<Production> neighborProductions = objectProductions(neighborType);
    for (RouterId router : program.snapshot().routers()) {
      if (router.equals(change.router()) || !sameDesignAs(program, router, designAs)) {
        continue;
      }
      Map<String, List<Statement>> groups = new LinkedHashMap<>();
      for (Statement statement : program.statements(router)) {
        Production.Match match = statement.match().orElse(null);
        if (match == null
            || !neighborProductions.contains(match.production())
            || !match.values("peer").equals(List.of(change.before()))) {
          continue;
        }
        Map<String, List<String>> values = new HashMap<>(match.values());
        values.remove("peer");
        String key = match.production().id() + "|" + values;
        groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(statement);
      }
      boolean otherOwner = owners.contains(router);
      List<EditOperator> remote = new ArrayList<>();
      for (List<Statement> group : groups.values()) {
        Production production = group.getFirst().match().orElseThrow().production();
        boolean reciprocalBinding =
            production.id().equals("neighbor-peer-group")
                && reciprocalGroup
                    .map(
                        expected ->
                            group
                                .getFirst()
                                .match()
                                .orElseThrow()
                                .values("group")
                                .equals(List.of(expected)))
                    .orElse(false);
        boolean alternativeBinding =
            production.id().equals("neighbor-peer-group")
                || production.id().equals("neighbor-remote-as");
        if (owners.size() == 1
            || otherOwner
            || group.size() >= owners.size()
            || reciprocalBinding
            || !alternativeBinding) {
          remote.add(
              new EditOperator.ReplaceSlots(
                  group.getLast(), "peer", List.of(change.after()), change.provenance()));
        }
      }
      if (!remote.isEmpty()) {
        alternatives.add(List.copyOf(remote));
      }
    }
    return List.copyOf(alternatives);
  }

  /**
   * Returns how the renamed owner is classified by another owner of the collapsed address.
   *
   * <p>A statement on an owner cannot denote that owner's own BGP session. Its peer-group binding
   * therefore identifies the other owner without relying on router names or line order. The value
   * disambiguates otherwise collapsed bindings on third routers.
   */
  private static Optional<String> reciprocalPeerGroup(
      ProgramModel program, Change change, List<RouterId> owners) {
    Set<String> groups = new HashSet<>();
    for (RouterId owner : owners) {
      if (owner.equals(change.router())) {
        continue;
      }
      for (Statement statement : program.statements(owner)) {
        Production.Match match = statement.match().orElse(null);
        if (match != null
            && match.production().id().equals("neighbor-peer-group")
            && match.values("peer").equals(List.of(change.before()))
            && match.values("group").size() == 1) {
          groups.add(match.values("group").getFirst());
        }
      }
    }
    return groups.size() == 1 ? Optional.of(groups.iterator().next()) : Optional.empty();
  }

  /** Removes a direct remote-as made redundant by a dependency-closed peer-group binding. */
  private static void removeRedundantRemoteAs(
      ProgramModel program, Change change, List<EditOperator> operators) {
    Map<RouterId, Set<String>> inherited = new HashMap<>();
    for (EditOperator operator : operators) {
      if (!(operator instanceof EditOperator.ReplaceSlots replace)) {
        continue;
      }
      Production.Match match = replace.statement().match().orElse(null);
      if (match == null || !match.production().id().equals("neighbor-peer-group")) {
        continue;
      }
      List<String> peer = replace.values().getOrDefault("peer", match.values("peer"));
      List<String> group = replace.values().getOrDefault("group", match.values("group"));
      if (!peer.equals(List.of(change.after())) || group.size() != 1) {
        continue;
      }
      program
          .object(new ProgramObject.Ref("peer-group", operator.router(), group))
          .ifPresent(
              peerGroup ->
                  inherited
                      .computeIfAbsent(operator.router(), ignored -> new HashSet<>())
                      .addAll(peerGroup.values("remote-as")));
    }
    operators.removeIf(
        operator -> {
          if (!(operator instanceof EditOperator.InsertStatement insertion)
              || !insertion.production().id().equals("neighbor-remote-as")
              || !insertion
                  .values()
                  .getOrDefault("peer", List.of())
                  .equals(List.of(change.after()))) {
            return false;
          }
          return inherited
              .getOrDefault(insertion.router(), Set.of())
              .containsAll(insertion.values().getOrDefault("asn", List.of()));
        });
  }

  /** Returns every production whose statement is bound to an object, including its attributes. */
  private static Set<Production> objectProductions(ObjectType type) {
    Set<Production> productions = new HashSet<>(type.sources());
    for (Attribute attribute : type.attributes().values()) {
      switch (attribute.source()) {
        case Attribute.Source.Slots slots ->
            slots.slots().stream().map(Attribute.SlotRef::production).forEach(productions::add);
        case Attribute.Source.Presence presence -> productions.addAll(presence.productions());
        default -> {}
      }
    }
    return Set.copyOf(productions);
  }

  private static boolean sameDesignAs(ProgramModel program, RouterId router, String expected) {
    if (expected.isEmpty()) {
      return false;
    }
    return program
        .object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()))
        .flatMap(object -> object.value("design-asn"))
        .filter(expected::equals)
        .isPresent();
  }
}
