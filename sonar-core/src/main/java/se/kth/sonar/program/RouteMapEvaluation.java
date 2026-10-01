package se.kth.sonar.program;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import se.kth.sonar.network.Prefix;

/** Evaluates the prefix-dependent part of a Cisco route map from the typed program. */
public final class RouteMapEvaluation {
  private RouteMapEvaluation() {}

  /**
   * Returns whether {@code routeMap} has a permit path for {@code prefix}.
   *
   * <p>Clauses whose result depends on non-prefix state, such as a route tag or community, do not
   * establish a prefix decision and are skipped. Prefix-list first-match and route-map first-match
   * semantics are otherwise exact; falling through the route map is an implicit deny.
   */
  public static boolean permits(ProgramModel program, ProgramObject.Ref routeMap, Prefix prefix) {
    return permits(program, routeMap, prefix, Set.of());
  }

  private static boolean permits(
      ProgramModel program,
      ProgramObject.Ref routeMap,
      Prefix prefix,
      Set<ProgramObject.Ref> completedLists) {
    if (!routeMap.type().equals("route-map") || routeMap.key().isEmpty()) {
      return false;
    }
    String name = routeMap.key().getFirst();
    List<ProgramObject> clauses =
        program.objectsOf(routeMap.router()).stream()
            .filter(object -> object.ref().type().equals("route-map-entry"))
            .filter(object -> object.ref().key().getFirst().equals(name))
            .sorted(Comparator.comparingLong(RouteMapEvaluation::sequence))
            .toList();
    for (ProgramObject clause : clauses) {
      if (clause.has("match-community") || clause.has("match-tag")) {
        continue;
      }
      List<String> lists = clause.values("match-prefix-list");
      if (!lists.isEmpty()
          && lists.stream()
              .map(list -> prefixListRef(routeMap, list))
              .noneMatch(
                  list ->
                      completedLists.contains(list) || prefixListPermits(program, list, prefix))) {
        continue;
      }
      return clause.value("action").filter("permit"::equals).isPresent();
    }
    return false;
  }

  /**
   * Returns prefix lists whose completion for every destination would make {@code routeMap} permit
   * every destination.
   *
   * <p>This is a symbolic dependency query: it evaluates IOS first-match semantics with one list
   * assumed to permit the requested destinations. It therefore excludes lists hidden behind an
   * earlier deny and does not depend on route-map or prefix-list names.
   */
  public static List<ProgramObject.Ref> completionLists(
      ProgramModel program, ProgramObject.Ref routeMap, Set<Prefix> destinations) {
    if (!routeMap.type().equals("route-map") || routeMap.key().isEmpty()) {
      return List.of();
    }
    Set<ProgramObject.Ref> referenced = new LinkedHashSet<>();
    for (ProgramObject clause : clauses(program, routeMap)) {
      if (clause.value("action").filter("permit"::equals).isEmpty()) {
        continue;
      }
      clause.values("match-prefix-list").stream()
          .map(list -> prefixListRef(routeMap, list))
          .filter(list -> program.object(list).isPresent())
          .forEach(referenced::add);
    }
    return referenced.stream()
        .filter(
            list ->
                destinations.stream()
                    .allMatch(prefix -> permits(program, routeMap, prefix, Set.of(list))))
        .sorted(java.util.Comparator.comparing(ProgramObject.Ref::toString))
        .toList();
  }

  /**
   * Returns whether {@code routeMap} certainly rejects {@code prefix} under the typed information
   * available in the configuration program.
   *
   * <p>Prefix-list matches are exact. A matching clause that also depends on a route tag or
   * community is not enough to prove either outcome, so the method returns false rather than
   * treating uncertainty as a denial. Falling through a non-empty route map is the IOS implicit
   * deny.
   */
  public static boolean definitelyDenies(
      ProgramModel program, ProgramObject.Ref routeMap, Prefix prefix) {
    if (!routeMap.type().equals("route-map") || routeMap.key().isEmpty()) {
      return false;
    }
    String name = routeMap.key().getFirst();
    List<ProgramObject> clauses =
        program.objectsOf(routeMap.router()).stream()
            .filter(object -> object.ref().type().equals("route-map-entry"))
            .filter(object -> object.ref().key().getFirst().equals(name))
            .sorted(Comparator.comparingLong(RouteMapEvaluation::sequence))
            .toList();
    for (ProgramObject clause : clauses) {
      if (!matchesPrefix(program, routeMap, clause, prefix)) {
        continue;
      }
      if (clause.has("match-community") || clause.has("match-tag")) {
        return false;
      }
      return clause.value("action").filter("deny"::equals).isPresent();
    }
    return !clauses.isEmpty();
  }

  /**
   * Returns the deny clauses that can precede a prefix-permitting path through {@code routeMap}.
   *
   * <p>A prefix-list match is decided exactly. A tag or community match depends on route state
   * unavailable in the configuration program, so a deny clause carrying one is a possible blocker
   * rather than being silently ignored. Evaluation stops at the first unconditional matching
   * clause, just as IOS route-map evaluation does.
   */
  public static List<ProgramObject> possibleBlockers(
      ProgramModel program, ProgramObject.Ref routeMap, Prefix prefix) {
    if (!routeMap.type().equals("route-map") || routeMap.key().isEmpty()) {
      return List.of();
    }
    String name = routeMap.key().getFirst();
    List<ProgramObject> blockers = new java.util.ArrayList<>();
    List<ProgramObject> clauses =
        program.objectsOf(routeMap.router()).stream()
            .filter(object -> object.ref().type().equals("route-map-entry"))
            .filter(object -> object.ref().key().getFirst().equals(name))
            .sorted(Comparator.comparingLong(RouteMapEvaluation::sequence))
            .toList();
    for (ProgramObject clause : clauses) {
      if (!matchesPrefix(program, routeMap, clause, prefix)) {
        continue;
      }
      if (clause.value("action").filter("deny"::equals).isPresent()) {
        blockers.add(clause);
      }
      if (!clause.has("match-community") && !clause.has("match-tag")) {
        break;
      }
    }
    return List.copyOf(blockers);
  }

  private static boolean matchesPrefix(
      ProgramModel program, ProgramObject.Ref routeMap, ProgramObject clause, Prefix prefix) {
    List<String> lists = clause.values("match-prefix-list");
    return lists.isEmpty()
        || lists.stream().anyMatch(list -> prefixListPermits(program, routeMap, list, prefix));
  }

  private static boolean prefixListPermits(
      ProgramModel program, ProgramObject.Ref routeMap, String reference, Prefix candidate) {
    return prefixListPermits(program, prefixListRef(routeMap, reference), candidate);
  }

  private static ProgramObject.Ref prefixListRef(ProgramObject.Ref routeMap, String reference) {
    return ProgramObject.Ref.isReference(reference, "prefix-list")
        ? ProgramObject.Ref.parse(reference)
        : new ProgramObject.Ref("prefix-list", routeMap.router(), List.of(reference));
  }

  private static List<ProgramObject> clauses(ProgramModel program, ProgramObject.Ref routeMap) {
    if (!routeMap.type().equals("route-map") || routeMap.key().isEmpty()) {
      return List.of();
    }
    String name = routeMap.key().getFirst();
    return program.objectsOf(routeMap.router()).stream()
        .filter(object -> object.ref().type().equals("route-map-entry"))
        .filter(object -> object.ref().key().getFirst().equals(name))
        .sorted(Comparator.comparingLong(RouteMapEvaluation::sequence))
        .toList();
  }

  /** Returns whether a prefix list permits {@code candidate} under IOS first-match semantics. */
  public static boolean prefixListPermits(
      ProgramModel program, ProgramObject.Ref prefixList, Prefix candidate) {
    if (!prefixList.type().equals("prefix-list") || prefixList.key().isEmpty()) {
      return false;
    }
    String name = prefixList.key().getFirst();
    List<ProgramObject> entries =
        program.objectsOf(prefixList.router()).stream()
            .filter(object -> object.ref().type().equals("prefix-list-entry"))
            .filter(object -> object.ref().key().getFirst().equals(name))
            .sorted(Comparator.comparingLong(RouteMapEvaluation::sequence))
            .toList();
    for (ProgramObject entry : entries) {
      if (prefixListEntryMatches(entry, candidate)) {
        return entry.value("action").filter("permit"::equals).isPresent();
      }
    }
    return false;
  }

  /** Returns whether one typed prefix-list entry matches {@code candidate}. */
  public static boolean prefixListEntryMatches(ProgramObject entry, Prefix candidate) {
    Prefix base = entry.value("prefix").flatMap(Prefix::tryParse).orElse(null);
    if (base == null) {
      return false;
    }
    int lower = entry.value("ge").map(Integer::parseInt).orElse(base.length());
    int upper = entry.value("le").map(Integer::parseInt).orElse(base.length());
    return base.contains(candidate) && candidate.length() >= lower && candidate.length() <= upper;
  }

  private static long sequence(ProgramObject object) {
    try {
      return Long.parseLong(object.ref().key().getLast());
    } catch (NumberFormatException ignored) {
      return Long.MAX_VALUE;
    }
  }
}
