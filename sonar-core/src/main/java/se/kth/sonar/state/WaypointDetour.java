package se.kth.sonar.state;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/** Finds the session policy objects that decide whether a flow turns to a required waypoint. */
public final class WaypointDetour {
  private WaypointDetour() {}

  /**
   * Counts, for every policy object, the violated waypoint obligations whose delivered path
   * bypasses the required waypoint although a router on that path has an external BGP session with
   * it.
   *
   * <p>Such a session is where the path could have turned, and BGP selects along it by the
   * attributes its policies set. A hop before the end of the path can hand the flow to the
   * waypoint: the route it would follow is exported by the waypoint and imported by the hop. A hop
   * after the start of the path can take the flow back: the route is exported by the hop and
   * imported by the waypoint. Each direction contributes its {@link SessionPolicy} slice, and each
   * distinct violated predicate counts once per object.
   */
  public static Map<ProgramObject.Ref, Integer> policies(RepairState state) {
    DataPlaneEvidence evidence = state.verification().evidence();
    Map<ProgramObject.Ref, Integer> counts = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      if (!(specification.predicate() instanceof ForwardingPredicate.Waypointing waypointing)
          || !specification.status().expectPresent()
          || !seen.add(waypointing.key())) {
        continue;
      }
      RouterId waypoint = waypointing.waypoint();
      Set<ProgramObject.Ref> observation = new LinkedHashSet<>();
      for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(waypointing, List.of())) {
        if (trace.disposition() != DataPlaneEvidence.Disposition.ACCEPTED
            || trace.hops().contains(waypoint)) {
          continue;
        }
        int last = trace.hops().size() - 1;
        for (int at = 0; at <= last; at++) {
          RouterId hop = trace.hops().get(at);
          if (at < last) {
            SessionPolicy.add(
                state.program(), hop, waypoint, waypointing.destination(), observation);
          }
          if (at > 0) {
            SessionPolicy.add(
                state.program(), waypoint, hop, waypointing.destination(), observation);
          }
        }
      }
      observation.forEach(object -> counts.merge(object, 1, Integer::sum));
    }
    return Map.copyOf(counts);
  }
}
