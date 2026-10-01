package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Ipv4;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;

/** Restores a physical link as one two-ended address and routing-profile transaction. */
public final class LinkProfileGenerator {
  private LinkProfileGenerator() {}

  /**
   * Returns link profiles anchored at either observed endpoint address.
   *
   * <p>A physical link's prefix length is inferred by leave-one-link-out agreement with the other
   * well-formed links. Anchoring at both endpoints represents the unavoidable symmetry over which
   * endpoint was corrupted. Existing addresses that fit the inferred subnet are preserved, and
   * exact OSPF coverage statements follow the repaired interface definition.
   */
  public static List<Hypothesis> generate(ProgramModel program, RelationInstance link) {
    ProgramObject left = endpoint(program, link, "a");
    ProgramObject right = endpoint(program, link, "b");
    Optional<Integer> length = consensusLength(program, link);
    if (left == null || right == null || length.isEmpty()) {
      return List.of();
    }
    Optional<Prefix> leftSubnet = left.value("subnet").flatMap(Prefix::tryParse);
    Optional<Prefix> rightSubnet = right.value("subnet").flatMap(Prefix::tryParse);
    if (leftSubnet.isPresent() && leftSubnet.equals(rightSubnet)) {
      return List.of();
    }
    Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    for (ProgramObject anchor : List.of(left, right)) {
      anchor
          .value("address")
          .flatMap(Ipv4::tryParse)
          .map(address -> new Prefix(address.and(Ipv4.mask(length.orElseThrow())), length.get()))
          .flatMap(target -> profile(program, left, right, target))
          .ifPresent(hypothesis -> unique.putIfAbsent(hypothesis.id(), hypothesis));
    }
    return List.copyOf(unique.values());
  }

  private static Optional<Hypothesis> profile(
      ProgramModel program, ProgramObject left, ProgramObject right, Prefix target) {
    Ipv4 leftBefore = left.value("address").flatMap(Ipv4::tryParse).orElse(null);
    Ipv4 rightBefore = right.value("address").flatMap(Ipv4::tryParse).orElse(null);
    if (leftBefore == null || rightBefore == null) {
      return Optional.empty();
    }
    Set<Ipv4> used = new HashSet<>();
    for (ProgramObject object : program.objects("interface")) {
      if (!object.ref().equals(left.ref()) && !object.ref().equals(right.ref())) {
        object.value("address").flatMap(Ipv4::tryParse).ifPresent(used::add);
      }
    }
    List<Ipv4> available = new ArrayList<>(CiscoIosSemantics.hosts(target, used));
    Ipv4 leftAfter = target.contains(leftBefore) && !used.contains(leftBefore) ? leftBefore : null;
    if (leftAfter != null) {
      available.remove(leftAfter);
    }
    Ipv4 rightAfter =
        target.contains(rightBefore)
                && !used.contains(rightBefore)
                && !rightBefore.equals(leftAfter)
            ? rightBefore
            : null;
    if (rightAfter != null) {
      available.remove(rightAfter);
    }
    if (leftAfter == null && !available.isEmpty()) {
      leftAfter = available.removeFirst();
    }
    if (rightAfter == null && !available.isEmpty()) {
      rightAfter = available.removeFirst();
    }
    if (leftAfter == null || rightAfter == null || leftAfter.equals(rightAfter)) {
      return Optional.empty();
    }
    Provenance provenance = new Provenance.Inferred("physical-link profile agreement");
    List<EditOperator> operators = new ArrayList<>();
    address(program, left, leftAfter, target, provenance).ifPresent(operators::add);
    address(program, right, rightAfter, target, provenance).ifPresent(operators::add);
    coverage(program, left, leftBefore, target, provenance).ifPresent(operators::add);
    coverage(program, right, rightBefore, target, provenance).ifPresent(operators::add);
    try {
      return operators.isEmpty()
          ? Optional.empty()
          : Optional.of(Hypothesis.of(program.snapshot(), operators));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return Optional.empty();
    }
  }

  private static Optional<EditOperator> address(
      ProgramModel program,
      ProgramObject endpoint,
      Ipv4 address,
      Prefix prefix,
      Provenance provenance) {
    return statement(program, endpoint, "ip-address")
        .map(
            value ->
                new EditOperator.ReplaceSlots(
                    value,
                    Map.of(
                        "address", List.of(address.toString()),
                        "mask", List.of(prefix.mask().toString())),
                    provenance));
  }

  /** Moves an OSPF statement only when it exclusively covered this interface before the repair. */
  private static Optional<EditOperator> coverage(
      ProgramModel program,
      ProgramObject endpoint,
      Ipv4 before,
      Prefix target,
      Provenance provenance) {
    ProgramObject best = null;
    Prefix bestPrefix = null;
    for (ProgramObject network : program.objectsOf(endpoint.ref().router())) {
      if (!network.ref().type().equals("ospf-network")) {
        continue;
      }
      Optional<Prefix> prefix = ospfPrefix(network);
      if (prefix.isEmpty() || !prefix.get().contains(before)) {
        continue;
      }
      long covered =
          program.objectsOf(endpoint.ref().router()).stream()
              .filter(object -> object.ref().type().equals("interface"))
              .flatMap(object -> object.value("address").flatMap(Ipv4::tryParse).stream())
              .filter(prefix.get()::contains)
              .count();
      if (covered == 1 && (bestPrefix == null || prefix.get().length() > bestPrefix.length())) {
        best = network;
        bestPrefix = prefix.get();
      }
    }
    if (best == null) {
      return Optional.empty();
    }
    Ipv4 wildcard = new Ipv4(~target.mask().bits());
    return statement(program, best, "ospf-network")
        .map(
            value ->
                new EditOperator.ReplaceSlots(
                    value,
                    Map.of(
                        "network", List.of(target.network().toString()),
                        "wildcard", List.of(wildcard.toString())),
                    provenance));
  }

  private static Optional<Prefix> ospfPrefix(ProgramObject object) {
    Optional<Ipv4> network = object.value("network").flatMap(Ipv4::tryParse);
    Optional<Ipv4> wildcard = object.value("wildcard").flatMap(Ipv4::tryParse);
    if (network.isEmpty() || wildcard.isEmpty() || wildcard.get().wildcardLength().isEmpty()) {
      return Optional.empty();
    }
    int length = wildcard.get().wildcardLength().getAsInt();
    return Optional.of(new Prefix(network.get().and(Ipv4.mask(length)), length));
  }

  private static Optional<Statement> statement(
      ProgramModel program, ProgramObject object, String production) {
    Set<se.kth.sonar.text.SourceSpan> spans = new HashSet<>(object.statements());
    return program.statements(object.ref().router()).stream()
        .filter(value -> spans.contains(value.span()))
        .filter(
            value ->
                value
                    .match()
                    .map(Production.Match::production)
                    .map(Production::id)
                    .filter(production::equals)
                    .isPresent())
        .findFirst();
  }

  private static Optional<Integer> consensusLength(
      ProgramModel program, RelationInstance excluded) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (RelationInstance link : program.instances("link")) {
      if (link.id().equals(excluded.id())) {
        continue;
      }
      ProgramObject left = endpoint(program, link, "a");
      ProgramObject right = endpoint(program, link, "b");
      Optional<Prefix> a =
          left == null ? Optional.empty() : left.value("subnet").flatMap(Prefix::tryParse);
      Optional<Prefix> b =
          right == null ? Optional.empty() : right.value("subnet").flatMap(Prefix::tryParse);
      if (a.isPresent() && a.equals(b)) {
        counts.merge(a.get().length(), 1, Integer::sum);
      }
    }
    return counts.entrySet().stream()
        .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey);
  }

  private static ProgramObject endpoint(ProgramModel program, RelationInstance link, String role) {
    ProgramObject.Ref ref = link.roles().get(role);
    return ref == null ? null : program.object(ref).orElse(null);
  }
}
