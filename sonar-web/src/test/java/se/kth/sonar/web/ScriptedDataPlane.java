package se.kth.sonar.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import se.kth.sonar.batfish.DataPlaneInspector;
import se.kth.sonar.batfish.ForwardingEntry;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * A data plane for the test dataset that holds a fixed set of predicates whatever the
 * configurations say, with a no-route trace for r1 to 10.0.0.2/32 and a small forwarding table.
 */
public final class ScriptedDataPlane implements DataPlaneInspector, DataPlaneEngine {
  private static final Set<String> HOLDING =
      Set.of(
          "reachability|r1|10.0.0.3/32",
          "isolation|r3|10.9.0.0/24",
          "reachability|r2|10.0.0.1/32",
          "waypointing|r1|10.0.0.3/32|r2");

  /** Returns what the data plane observes for {@code specifications}. */
  public static Observation observe(SpecificationSet specifications) {
    Set<ForwardingPredicate> holding =
        specifications.all().stream()
            .map(Specification::predicate)
            .filter(predicate -> HOLDING.contains(predicate.key()))
            .collect(Collectors.toSet());
    ForwardingPredicate traced =
        new ForwardingPredicate.Reachability(RouterId.of("r1"), Prefix.parse("10.0.0.2/32"));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                traced,
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(RouterId.of("r1"), RouterId.of("r2")),
                        DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(Prefix.parse("10.0.0.3/32"), Set.of(RouterId.of("r3"))),
            Map.of(
                Prefix.parse("10.0.0.3/32"),
                Set.of(RouterId.of("r1"), RouterId.of("r2"), RouterId.of("r3"))));
    return new Observation(holding, evidence);
  }

  /** Returns the forwarding table the data plane reports. */
  public static List<ForwardingEntry> forwarding() {
    return List.of(
        entry("r1", "10.0.0.2/32", Optional.empty(), Optional.empty(), "no_route", Set.of()),
        entry(
            "r1",
            "10.0.0.3/32",
            Optional.of("10.1.1.0"),
            Optional.of("GigabitEthernet0/0"),
            "forwarded",
            Set.of()),
        entry(
            "r2",
            "10.0.0.1/32",
            Optional.of("10.1.1.1"),
            Optional.of("GigabitEthernet0/0"),
            "forwarded_blocked_incoming_filter",
            Set.of("r1")),
        entry(
            "r2",
            "10.0.0.3/32",
            Optional.of("10.2.2.0"),
            Optional.of("GigabitEthernet0/1"),
            "forwarded",
            Set.of()),
        entry(
            "r3", "10.0.0.3/32", Optional.empty(), Optional.of("Loopback0"), "accepted", Set.of()));
  }

  private static ForwardingEntry entry(
      String node,
      String prefix,
      Optional<String> nextHopIp,
      Optional<String> nextHopInterface,
      String action,
      Set<String> receivers) {
    return new ForwardingEntry(node, prefix, nextHopIp, nextHopInterface, action, receivers);
  }

  @Override
  public Report inspect(Snapshot snapshot, SpecificationSet specifications) {
    return new Report(observe(specifications), forwarding());
  }

  @Override
  public Observation analyze(Snapshot snapshot, SpecificationSet specifications) {
    return observe(specifications);
  }

  @Override
  public void close() {}
}
