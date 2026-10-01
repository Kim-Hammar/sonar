package se.kth.sonar.prior;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.verify.DataPlaneEvidence;

/** Weights physical-link objects that distinguish satisfying and violating forwarding paths. */
public final class PathDifferentialFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private static final double HALF = 0.5;

  private final ContentCache<Evidence> observations = new ContentCache<>(CACHE_CAPACITY);

  private record Evidence(Map<RouterEvidence.Link, Integer> counts, int alternatives) {}

  @Override
  public String name() {
    return "path-differential";
  }

  @Override
  public double weight(Region region, RepairState state) {
    Evidence evidence = observations.get(state.verification().digest(), () -> observations(state));
    double weight = 1;
    for (Map.Entry<RouterEvidence.Link, Integer> entry : evidence.counts().entrySet()) {
      if (!edits(region, entry.getKey(), state)) {
        continue;
      }
      double reduction =
          (double) Math.max(1, state.problem().topology().links().size())
              / Math.max(1, evidence.alternatives());
      weight = Math.max(weight, reduction * (entry.getValue() + HALF) / HALF);
    }
    return weight;
  }

  /** Counts each link in the symmetric difference of good and bad paths once per obligation. */
  private static Evidence observations(RepairState state) {
    Map<RouterEvidence.Link, Integer> counts = new HashMap<>();
    Set<RouterEvidence.Link> alternatives = new HashSet<>();
    Set<RouterEvidence.Link> physical = new HashSet<>();
    state
        .problem()
        .topology()
        .links()
        .forEach(
            link -> physical.add(RouterEvidence.Link.of(link.a().router(), link.b().router())));
    Set<String> seen = new HashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      if (!(specification.predicate() instanceof ForwardingPredicate.Waypointing waypointing)
          || !seen.add(specification.predicate().key())) {
        continue;
      }
      List<DataPlaneEvidence.Trace> accepted =
          state.verification().evidence().traces().getOrDefault(waypointing, List.of()).stream()
              .filter(trace -> trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED)
              .toList();
      List<Set<RouterEvidence.Link>> satisfying =
          accepted.stream()
              .filter(
                  trace ->
                      trace.hops().contains(waypointing.waypoint())
                          == specification.status().expectPresent())
              .map(PathDifferentialFactor::links)
              .toList();
      List<Set<RouterEvidence.Link>> violating =
          accepted.stream()
              .filter(
                  trace ->
                      trace.hops().contains(waypointing.waypoint())
                          != specification.status().expectPresent())
              .map(PathDifferentialFactor::links)
              .toList();
      Set<RouterEvidence.Link> differential = new LinkedHashSet<>();
      for (Set<RouterEvidence.Link> good : satisfying) {
        for (Set<RouterEvidence.Link> bad : violating) {
          Set<RouterEvidence.Link> difference = new LinkedHashSet<>(good);
          difference.addAll(bad);
          Set<RouterEvidence.Link> common = new HashSet<>(good);
          common.retainAll(bad);
          difference.removeAll(common);
          difference.retainAll(physical);
          differential.addAll(difference);
        }
      }
      alternatives.addAll(differential);
      differential.forEach(link -> counts.merge(link, 1, Integer::sum));
    }
    return new Evidence(Map.copyOf(counts), alternatives.size());
  }

  private static Set<RouterEvidence.Link> links(DataPlaneEvidence.Trace trace) {
    Set<RouterEvidence.Link> result = new LinkedHashSet<>();
    for (int at = 1; at < trace.hops().size(); at++) {
      result.add(RouterEvidence.Link.of(trace.hops().get(at - 1), trace.hops().get(at)));
    }
    return Set.copyOf(result);
  }

  /** Returns whether {@code region} edits the exact physical-link object. */
  private static boolean edits(Region region, RouterEvidence.Link target, RepairState state) {
    if (region instanceof RelationRegion relation
        && relation.instance().relation().equals("link")) {
      return relation.routers().contains(target.first())
          && relation.routers().contains(target.second());
    }
    if (!(region instanceof SiteRegion site) || !site.site().type().equals("interface")) {
      return false;
    }
    for (se.kth.sonar.network.Topology.Link link : state.problem().topology().links()) {
      if (!target.equals(RouterEvidence.Link.of(link.a().router(), link.b().router()))) {
        continue;
      }
      for (se.kth.sonar.network.Topology.Endpoint endpoint : List.of(link.a(), link.b())) {
        ProgramObject.Ref ref =
            new ProgramObject.Ref(
                "interface", endpoint.router(), List.of(endpoint.interfaceName()));
        if (ref.equals(site.site())) {
          return true;
        }
      }
    }
    return false;
  }
}
