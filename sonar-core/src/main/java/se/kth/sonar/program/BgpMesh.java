package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.text.RouterId;

/**
 * A missing iBGP mesh incidence required by formal path evidence.
 *
 * <p>The member has an owned loopback and a design AS but no BGP process. Its peers are implicated
 * routers in the same AS that already run BGP. The whole relation is one transaction: create the
 * process and establish direct, reciprocal loopback sessions to every observed peer. Direct
 * sessions avoid inventing a peer-group name when the local AS has no surviving iBGP convention.
 *
 * @param member the processless router
 * @param peers the implicated same-AS BGP speakers
 * @param asn the shared autonomous-system number
 */
public record BgpMesh(ProgramObject.Ref member, List<ProgramObject.Ref> peers, String asn) {
  /** Copies and checks the components. */
  public BgpMesh {
    java.util.Objects.requireNonNull(member, "member");
    peers = List.copyOf(peers);
    if (peers.isEmpty() || asn.isEmpty()) {
      throw new IllegalArgumentException("a BGP mesh needs peers and an AS number");
    }
  }

  /** Returns a stable identifier. */
  public ContentHash id() {
    ContentHash.Builder digest = ContentHash.builder().add("bgp-mesh").add(member.toString());
    peers.forEach(peer -> digest.add(peer.toString()));
    return digest.build();
  }

  /** Returns every participating router object. */
  public Set<ProgramObject.Ref> objects() {
    Set<ProgramObject.Ref> result = new LinkedHashSet<>();
    result.add(member);
    result.addAll(peers);
    return Set.copyOf(result);
  }

  /** Returns every participating router. */
  public NavigableSet<RouterId> routers() {
    NavigableSet<RouterId> result = new TreeSet<>();
    objects().forEach(object -> result.add(object.router()));
    return java.util.Collections.unmodifiableNavigableSet(result);
  }

  /** Infers processless members named by a formal obligation and their implicated same-AS peers. */
  public static List<BgpMesh> infer(
      ProgramModel program, Collection<RouterId> relevant, Collection<RouterId> processRequired) {
    Set<RouterId> implicated = Set.copyOf(relevant);
    Set<RouterId> required = Set.copyOf(processRequired);
    Map<RouterId, ProgramObject> routers = new LinkedHashMap<>();
    program.objects("router").forEach(router -> routers.put(router.ref().router(), router));
    List<BgpMesh> result = new ArrayList<>();
    for (RouterId id : required) {
      ProgramObject member = routers.get(id);
      if (member == null
          || !implicated.contains(id)
          || member.has("bgp-asn")
          || !member.has("loopback-address")) {
        continue;
      }
      String asn = member.value("design-asn").orElse("");
      if (asn.isEmpty()) {
        continue;
      }
      List<ProgramObject.Ref> peers = new ArrayList<>();
      for (ProgramObject peer : routers.values()) {
        if (!peer.ref().equals(member.ref())
            && implicated.contains(peer.ref().router())
            && peer.has("loopback-address")
            && peer.value("bgp-asn").filter(asn::equals).isPresent()) {
          peers.add(peer.ref());
        }
      }
      if (!peers.isEmpty()) {
        result.add(new BgpMesh(member.ref(), peers, asn));
      }
    }
    return List.copyOf(result);
  }
}
