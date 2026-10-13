package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.text.RouterId;

/**
 * Closes a repair of a direct BGP transfer, or of the physical link that carries it, over the
 * first-match filters on that link.
 */
public final class InterdomainTransferClosure {
  private InterdomainTransferClosure() {}

  /**
   * Returns policy candidates combined with the transport filters that otherwise shadow them.
   *
   * <p>A direct eBGP route transfer is one semantic relation: the BGP attachment and the ACLs on
   * the physical link must all permit the transfer. A leading unconditional deny makes every later
   * ACL rule unreachable, so changing the policy alone is observationally inert. This method
   * composes those already-derived repairs atomically; it invents neither policy names nor filter
   * rules.
   */
  public static List<Hypothesis> close(
      ProgramModel program, ProgramObject.Ref neighborSite, List<Hypothesis> policyCandidates) {
    if (!neighborSite.type().equals("bgp-neighbor")) {
      return List.of();
    }
    ProgramObject neighbor = program.object(neighborSite).orElse(null);
    RouterId peer = peerRouter(neighbor);
    if (neighbor == null || peer == null) {
      return List.of();
    }
    List<Hypothesis> blockers = blockers(program, neighborSite.router(), peer);
    if (blockers.isEmpty()) {
      return List.of();
    }
    Map<ContentHash, Hypothesis> result = new LinkedHashMap<>();
    for (Hypothesis policy : policyCandidates) {
      if (policy.operators().stream()
          .noneMatch(operator -> operator.shape().contains("neighbor-route-map"))) {
        continue;
      }
      for (Hypothesis blocker : blockers) {
        List<EditOperator> operators = new ArrayList<>(policy.operators());
        operators.addAll(blocker.operators());
        combined(program, operators)
            .ifPresent(combined -> result.putIfAbsent(combined.id(), combined));
      }
    }
    return List.copyOf(result.values());
  }

  /**
   * Returns the repairs of the physical link {@code link} combined with the transport filters that
   * otherwise shadow them.
   *
   * <p>A link whose ends disagree carries nothing, so a filter on it that denies everything goes
   * unobserved until the link is repaired, and then the repair alone trades one violation for
   * another. Each repair is therefore also offered together with every first-match repair of the
   * filters attached to the link's interfaces.
   */
  public static List<Hypothesis> closeLink(
      ProgramModel program, RelationInstance link, List<Hypothesis> repairs) {
    ProgramObject.Ref a = link.roles().get("a");
    ProgramObject.Ref b = link.roles().get("b");
    if (a == null || b == null) {
      return List.of();
    }
    Map<ContentHash, Hypothesis> result = new LinkedHashMap<>();
    for (Hypothesis blocker : blockers(program, a.router(), b.router())) {
      for (Hypothesis repair : repairs) {
        List<EditOperator> operators = new ArrayList<>(repair.operators());
        operators.addAll(blocker.operators());
        combined(program, operators)
            .ifPresent(combined -> result.putIfAbsent(combined.id(), combined));
      }
    }
    return List.copyOf(result.values());
  }

  /** Returns the candidate made of {@code operators}, unless they do not form one transaction. */
  private static Optional<Hypothesis> combined(ProgramModel program, List<EditOperator> operators) {
    try {
      return Optional.of(Hypothesis.of(program.snapshot(), operators));
    } catch (IllegalArgumentException | IllegalStateException incompatible) {
      return Optional.empty();
    }
  }

  /** Returns the direct BGP neighbors whose transport uses {@code site}. */
  public static List<ProgramObject.Ref> relatedNeighbors(
      ProgramModel program, ProgramObject.Ref site) {
    if (site.type().equals("bgp-neighbor")) {
      return program.object(site).isPresent() ? List.of(site) : List.of();
    }
    if (!site.type().equals("acl")) {
      return List.of();
    }
    Set<RouterId> peers = new LinkedHashSet<>();
    for (Topology.Link link : program.topology().links()) {
      if (uses(program, link.a(), site)) {
        peers.add(link.b().router());
      }
      if (uses(program, link.b(), site)) {
        peers.add(link.a().router());
      }
    }
    Set<ProgramObject.Ref> result = new LinkedHashSet<>();
    for (RouterId peer : peers) {
      addNeighbors(program, site.router(), peer, result);
      addNeighbors(program, peer, site.router(), result);
    }
    return List.copyOf(result);
  }

  private static boolean uses(
      ProgramModel program, Topology.Endpoint endpoint, ProgramObject.Ref acl) {
    if (!endpoint.router().equals(acl.router())) {
      return false;
    }
    ProgramObject.Ref interfaceRef =
        new ProgramObject.Ref("interface", endpoint.router(), List.of(endpoint.interfaceName()));
    ProgramObject value = program.object(interfaceRef).orElse(null);
    if (value == null) {
      return false;
    }
    return List.of("acl-in", "acl-out").stream()
        .flatMap(attribute -> value.values(attribute).stream())
        .map(reference -> reference(reference, endpoint.router()))
        .anyMatch(acl::equals);
  }

  private static void addNeighbors(
      ProgramModel program, RouterId local, RouterId remote, Set<ProgramObject.Ref> result) {
    String remoteRef = new ProgramObject.Ref("router", remote, List.of()).toString();
    program.objectsOf(local).stream()
        .filter(object -> object.ref().type().equals("bgp-neighbor"))
        .filter(object -> object.value("peer-router").filter(remoteRef::equals).isPresent())
        .map(ProgramObject::ref)
        .forEach(result::add);
  }

  private static List<Hypothesis> blockers(ProgramModel program, RouterId local, RouterId remote) {
    Map<ContentHash, Hypothesis> result = new LinkedHashMap<>();
    for (Topology.Link link : program.topology().links()) {
      if (!joins(link, local, remote)) {
        continue;
      }
      for (Topology.Endpoint endpoint : List.of(link.a(), link.b())) {
        ProgramObject.Ref interfaceRef =
            new ProgramObject.Ref(
                "interface", endpoint.router(), List.of(endpoint.interfaceName()));
        ProgramObject value = program.object(interfaceRef).orElse(null);
        if (value == null) {
          continue;
        }
        for (String attribute : List.of("acl-in", "acl-out")) {
          for (String reference : value.values(attribute)) {
            ProgramObject.Ref acl = reference(reference, endpoint.router());
            if (acl == null) {
              continue;
            }
            for (Hypothesis hypothesis : FirstMatchRepair.generate(program, acl)) {
              result.putIfAbsent(hypothesis.id(), hypothesis);
            }
          }
        }
      }
    }
    return List.copyOf(result.values());
  }

  private static boolean joins(Topology.Link link, RouterId first, RouterId second) {
    return (link.a().router().equals(first) && link.b().router().equals(second))
        || (link.a().router().equals(second) && link.b().router().equals(first));
  }

  private static ProgramObject.Ref reference(String value, RouterId router) {
    if (value.isBlank()) {
      return null;
    }
    try {
      return ProgramObject.Ref.isReference(value, "acl")
          ? ProgramObject.Ref.parse(value)
          : new ProgramObject.Ref("acl", router, List.of(value));
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private static RouterId peerRouter(ProgramObject neighbor) {
    if (neighbor == null) {
      return null;
    }
    return neighbor
        .value("peer-router")
        .filter(value -> ProgramObject.Ref.isReference(value, "router"))
        .map(ProgramObject.Ref::parse)
        .map(ProgramObject.Ref::router)
        .orElse(null);
  }
}
