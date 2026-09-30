package se.kth.sonar.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Which routers the verifier's evidence implicates for each specification: the source, the
 * waypoint, every hop of the traced flows, and the routers that originate the destination prefix
 * through an interface, a network statement or a static route.
 */
public final class RouterEvidence {
  private final RepairState state;
  private final Map<Prefix, Set<RouterId>> owners;

  private RouterEvidence(RepairState state) {
    this.state = state;
    this.owners = owners(state.program());
  }

  /** Reads the evidence of {@code state}. */
  public static RouterEvidence of(RepairState state) {
    return new RouterEvidence(Objects.requireNonNull(state, "state"));
  }

  /** Returns the routers that originate {@code prefix}. */
  public Set<RouterId> owners(Prefix prefix) {
    return owners.getOrDefault(prefix, Set.of());
  }

  /** Returns the routers implicated for {@code specification}, in evidence order. */
  public Set<RouterId> routers(Specification specification) {
    Set<RouterId> routers = new LinkedHashSet<>();
    ForwardingPredicate predicate = specification.predicate();
    routers.add(predicate.source());
    if (predicate instanceof ForwardingPredicate.Waypointing waypointing) {
      routers.add(waypointing.waypoint());
    }
    DataPlaneEvidence evidence = state.verification().evidence();
    for (DataPlaneEvidence.Trace trace : evidence.traces().getOrDefault(predicate, List.of())) {
      routers.addAll(trace.hops());
    }
    routers.addAll(owners(predicate.destination()));
    routers.removeIf(router -> !state.snapshot().contains(router));
    return routers;
  }

  /** Returns the unresolved specifications that implicate each router, in specification order. */
  public Map<RouterId, List<Specification>> unresolvedByRouter() {
    Map<RouterId, List<Specification>> implicated = new LinkedHashMap<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      for (RouterId router : routers(specification)) {
        implicated.computeIfAbsent(router, r -> new ArrayList<>()).add(specification);
      }
    }
    return implicated;
  }

  private static Map<Prefix, Set<RouterId>> owners(ProgramModel program) {
    Map<Prefix, Set<RouterId>> owners = new HashMap<>();
    for (ProgramObject object : program.objects()) {
      List<String> values = new ArrayList<>(object.values("prefix"));
      values.addAll(object.values("subnet"));
      object.value("address").ifPresent(address -> values.add(address + "/32"));
      for (String value : values) {
        Prefix.tryParse(value)
            .ifPresent(
                prefix ->
                    owners
                        .computeIfAbsent(prefix, p -> new LinkedHashSet<>())
                        .add(object.ref().router()));
      }
    }
    return owners;
  }
}
