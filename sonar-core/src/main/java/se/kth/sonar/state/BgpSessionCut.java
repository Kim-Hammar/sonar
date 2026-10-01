package se.kth.sonar.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/** An active reciprocal BGP relation contradicted by a formal path counterexample. */
public record BgpSessionCut(RelationInstance session, List<Specification> targets) {
  /** Copies and checks the components. */
  public BgpSessionCut {
    Objects.requireNonNull(session, "session");
    if (!session.relation().equals("bgp-session") || !session.satisfied()) {
      throw new IllegalArgumentException("a session cut suppresses a satisfied BGP session");
    }
    targets = List.copyOf(targets);
    if (targets.isEmpty()) {
      throw new IllegalArgumentException("a session cut needs a counterexample");
    }
  }

  /**
   * Infers exact session cuts from formal path counterexamples.
   *
   * <p>An accepted isolation counterexample identifies its first forbidden forwarding edge. An
   * accepted waypoint counterexample identifies an alternate source-to-reflector session when the
   * required source-to-waypoint session already exists: the alternate is a finite bypass of the
   * exact relation named by the specification. Only reciprocal, active loopback sessions are cut.
   */
  public static List<BgpSessionCut> infer(RepairState state) {
    Map<Link, LinkedHashSet<Specification>> forbidden = new LinkedHashMap<>();
    DataPlaneEvidence evidence = state.verification().evidence();
    for (Specification specification : state.verification().verdict().unresolved()) {
      if (!(specification.predicate() instanceof ForwardingPredicate.Isolation)) {
        continue;
      }
      for (DataPlaneEvidence.Trace trace :
          evidence.traces().getOrDefault(specification.predicate(), List.of())) {
        if (trace.hops().size() >= 2) {
          forbidden
              .computeIfAbsent(
                  Link.of(trace.hops().get(0), trace.hops().get(1)),
                  ignored -> new LinkedHashSet<>())
              .add(specification);
        }
      }
    }
    List<RelationInstance> active =
        state.program().instances("bgp-session").stream()
            .filter(RelationInstance::satisfied)
            .filter(session -> session.roles().size() == 2)
            .filter(session -> activeLoopbackSession(state.program(), session))
            .toList();
    for (Specification specification : state.verification().verdict().unresolved()) {
      if (!(specification.predicate() instanceof ForwardingPredicate.Waypointing waypointing)
          || waypointing.source().equals(waypointing.waypoint())
          || evidence.traces().getOrDefault(specification.predicate(), List.of()).stream()
              .noneMatch(
                  trace ->
                      trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED
                          && !trace.hops().contains(waypointing.waypoint()))) {
        continue;
      }
      Link required = Link.of(waypointing.source(), waypointing.waypoint());
      if (active.stream().noneMatch(session -> link(session).equals(required))) {
        continue;
      }
      for (RelationInstance session : active) {
        Link candidate = link(session);
        if (!candidate.equals(required) && candidate.contains(waypointing.source())) {
          forbidden.computeIfAbsent(candidate, ignored -> new LinkedHashSet<>()).add(specification);
        }
      }
    }
    List<BgpSessionCut> result = new ArrayList<>();
    for (RelationInstance session : active) {
      Set<Specification> targets = forbidden.get(link(session));
      if (targets != null) {
        result.add(new BgpSessionCut(session, List.copyOf(targets)));
      }
    }
    return List.copyOf(result);
  }

  private static boolean activeLoopbackSession(ProgramModel program, RelationInstance session) {
    ProgramObject local = program.object(session.roles().get("local")).orElse(null);
    ProgramObject remote = program.object(session.roles().get("remote")).orElse(null);
    if (local == null || remote == null || !active(local) || !active(remote)) {
      return false;
    }
    ProgramObject localRouter = router(program, local.ref().router());
    ProgramObject remoteRouter = router(program, remote.ref().router());
    if (localRouter == null || remoteRouter == null) {
      return false;
    }
    String localIdentity = localRouter.value("loopback-address").orElse("");
    String remoteIdentity = remoteRouter.value("loopback-address").orElse("");
    return local.ref().key().equals(List.of(remoteIdentity))
        && remote.ref().key().equals(List.of(localIdentity));
  }

  private static boolean active(ProgramObject neighbor) {
    return neighbor.has("activated") && !neighbor.has("shutdown");
  }

  private static ProgramObject router(ProgramModel program, RouterId router) {
    return program.object(new ProgramObject.Ref("router", router, List.of())).orElse(null);
  }

  private static Link link(RelationInstance session) {
    return Link.of(session.routers().first(), session.routers().last());
  }

  private record Link(RouterId first, RouterId second) {
    static Link of(RouterId left, RouterId right) {
      return left.compareTo(right) <= 0 ? new Link(left, right) : new Link(right, left);
    }

    boolean contains(RouterId router) {
      return first.equals(router) || second.equals(router);
    }
  }
}
