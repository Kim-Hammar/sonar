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

/** An active reciprocal BGP relation traversed by a formal isolation counterexample. */
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

  /** Infers exact session cuts from the first forwarding edge of violated isolation predicates. */
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
    if (forbidden.isEmpty()) {
      return List.of();
    }
    List<BgpSessionCut> result = new ArrayList<>();
    for (RelationInstance session : state.program().instances("bgp-session")) {
      if (!session.satisfied() || session.roles().size() != 2) {
        continue;
      }
      Link link = Link.of(session.routers().first(), session.routers().last());
      Set<Specification> targets = forbidden.get(link);
      if (targets != null && activeLoopbackSession(state.program(), session)) {
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

  private record Link(RouterId first, RouterId second) {
    static Link of(RouterId left, RouterId right) {
      return left.compareTo(right) <= 0 ? new Link(left, right) : new Link(right, left);
    }
  }
}
