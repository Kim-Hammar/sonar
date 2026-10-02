package se.kth.sonar.state;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.text.RouterId;

/** The objects that decide whether and how a route crosses an external BGP session. */
public final class SessionPolicy {
  /** The object types a session's policy slice consists of. */
  public static final List<String> TYPES = List.of("bgp-neighbor", "route-map-entry");

  private SessionPolicy() {}

  /**
   * Adds the slice of the route to {@code destination} that {@code sender} exports to {@code
   * receiver}: the two neighbor objects and, of the route maps attached in the route's direction,
   * the clauses that decide the destination. Only a session between autonomous systems has a slice:
   * an internal one passes the route on with the attributes set at the boundary.
   */
  static void add(
      ProgramModel program,
      RouterId receiver,
      RouterId sender,
      Prefix destination,
      Set<ProgramObject.Ref> slice) {
    if (!BgpPeering.external(program, receiver, sender)) {
      return;
    }
    for (ProgramObject neighbor : BgpPeering.sessions(program, receiver, sender)) {
      slice.add(neighbor.ref());
      addClauses(program, neighbor, "route-map-in", destination, slice);
    }
    for (ProgramObject neighbor : BgpPeering.sessions(program, sender, receiver)) {
      slice.add(neighbor.ref());
      addClauses(program, neighbor, "route-map-out", destination, slice);
    }
  }

  /** Adds the deciding clauses of the route maps {@code neighbor} attaches by {@code attribute}. */
  private static void addClauses(
      ProgramModel program,
      ProgramObject neighbor,
      String attribute,
      Prefix destination,
      Set<ProgramObject.Ref> slice) {
    for (String value : neighbor.values(attribute)) {
      Optional<ProgramObject.Ref> routeMap =
          Optional.of(
                  ProgramObject.Ref.isReference(value, "route-map")
                      ? ProgramObject.Ref.parse(value)
                      : new ProgramObject.Ref("route-map", neighbor.ref().router(), List.of(value)))
              .filter(map -> program.object(map).isPresent());
      if (routeMap.isEmpty()) {
        continue;
      }
      RouteMapEvaluation.decidingClauses(program, routeMap.get(), destination).stream()
          .map(ProgramObject::ref)
          .forEach(slice::add);
    }
  }
}
