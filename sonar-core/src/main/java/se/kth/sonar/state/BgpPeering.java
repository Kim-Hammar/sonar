package se.kth.sonar.state;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.text.RouterId;

/** Reads from the typed program which routers peer in BGP and across which boundary. */
final class BgpPeering {
  private BgpPeering() {}

  /** Returns the autonomous system {@code router} runs BGP in, if it runs BGP. */
  static Optional<String> asn(ProgramModel program, RouterId router) {
    return program
        .object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()))
        .flatMap(object -> object.value("bgp-asn"));
  }

  /** Returns whether both routers run BGP, in different autonomous systems. */
  static boolean external(ProgramModel program, RouterId first, RouterId second) {
    Optional<String> one = asn(program, first);
    return one.isPresent()
        && asn(program, second).filter(other -> !other.equals(one.get())).isPresent();
  }

  /** Returns the BGP neighbor objects of {@code local} whose peer address {@code peer} owns. */
  static List<ProgramObject> sessions(ProgramModel program, RouterId local, RouterId peer) {
    String owner = new ProgramObject.Ref(ObjectType.ROUTER, peer, List.of()).toString();
    return program.objectsOf(local).stream()
        .filter(object -> object.ref().type().equals("bgp-neighbor"))
        .filter(object -> object.value("peer-router").filter(owner::equals).isPresent())
        .toList();
  }

  /** Returns the routers that own the peer addresses of {@code router}'s BGP neighbor objects. */
  static Set<RouterId> peers(ProgramModel program, RouterId router) {
    Set<RouterId> peers = new LinkedHashSet<>();
    for (ProgramObject object : program.objectsOf(router)) {
      if (!object.ref().type().equals("bgp-neighbor")) {
        continue;
      }
      object
          .value("peer-router")
          .filter(value -> ProgramObject.Ref.isReference(value, ObjectType.ROUTER))
          .map(value -> ProgramObject.Ref.parse(value).router())
          .ifPresent(peers::add);
    }
    return peers;
  }
}
