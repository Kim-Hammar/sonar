package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.state.PolicyCut;

/** Generates repairs for locally contradictory first-match policy clauses. */
public final class FirstMatchRepair {
  private FirstMatchRepair() {}

  /**
   * Returns one transaction deleting every leading unconditional deny from {@code site}.
   *
   * <p>Under IOS first-match semantics, each such rule makes every later permit unreachable. The
   * maximal leading prefix is one semantic object: deleting only one of several identical blockers
   * cannot change the data plane. A sole deny-only ACL is retained because it has no shadowed rule.
   */
  public static List<Hypothesis> generate(ProgramModel program, ProgramObject.Ref site) {
    if (site.type().equals("route-map-entry")) {
      return routeMapEntry(program, site);
    }
    if (!site.type().equals("acl")) {
      return List.of();
    }
    ProgramObject acl = program.object(site).orElse(null);
    if (acl == null) {
      return List.of();
    }
    List<EditOperator> blockers = leadingBlockers(program, acl);
    if (blockers.isEmpty()) {
      return List.of();
    }
    Map<ContentHash, Hypothesis> hypotheses = new LinkedHashMap<>();
    add(program, blockers, hypotheses);
    for (ProgramObject object : program.objectsOf(site.router())) {
      if (!object.ref().type().equals("interface") || !references(object, site)) {
        continue;
      }
      List<EditOperator> complete = new ArrayList<>(blockers);
      for (String attribute : List.of("acl-in", "acl-out")) {
        for (String reference : object.values(attribute)) {
          ProgramObject.Ref sibling = reference(reference, site.router());
          if (sibling != null && !sibling.equals(site)) {
            program
                .object(sibling)
                .ifPresent(value -> complete.addAll(leadingBlockers(program, value)));
          }
        }
      }
      add(program, complete, hypotheses);
    }
    return List.copyOf(hypotheses.values());
  }

  /** Returns the maximal leading deny prefix of one ACL, unless it is the entire ACL. */
  private static List<EditOperator> leadingBlockers(ProgramModel program, ProgramObject acl) {
    List<Statement> rules =
        acl.statements().stream()
            .flatMap(
                span ->
                    program.statements(span.router()).stream()
                        .filter(statement -> statement.span().equals(span)))
            .filter(statement -> statement.match().isPresent())
            .filter(
                statement -> statement.match().orElseThrow().production().id().startsWith("acl-"))
            .sorted(Comparator.comparingInt(FirstMatchRepair::sequence))
            .toList();
    List<EditOperator> blockers = new ArrayList<>();
    for (Statement rule : rules) {
      var match = rule.match().orElseThrow();
      if (!match.production().id().equals("acl-any")
          || !match.values().getOrDefault("action", List.of()).equals(List.of("deny"))) {
        break;
      }
      blockers.addFirst(
          new EditOperator.DeleteStatement(
              rule, new Provenance.Inferred("first-match unreachable-rule analysis")));
    }
    return blockers.isEmpty() || blockers.size() == rules.size()
        ? List.of()
        : List.copyOf(blockers);
  }

  private static boolean references(ProgramObject object, ProgramObject.Ref acl) {
    return List.of("acl-in", "acl-out").stream()
        .flatMap(attribute -> object.values(attribute).stream())
        .map(value -> reference(value, object.ref().router()))
        .anyMatch(acl::equals);
  }

  private static ProgramObject.Ref reference(String value, se.kth.sonar.text.RouterId router) {
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

  private static void add(
      ProgramModel program, List<EditOperator> operators, Map<ContentHash, Hypothesis> hypotheses) {
    if (operators.isEmpty()) {
      return;
    }
    try {
      Hypothesis hypothesis = Hypothesis.of(program.snapshot(), operators);
      hypotheses.putIfAbsent(hypothesis.id(), hypothesis);
    } catch (IllegalArgumentException invalid) {
      return;
    }
  }

  /** Opens a deny clause whose transformations are otherwise semantically unreachable. */
  private static List<Hypothesis> routeMapEntry(ProgramModel program, ProgramObject.Ref site) {
    ProgramObject entry = program.object(site).orElse(null);
    if (entry == null
        || entry.value("action").filter("deny"::equals).isEmpty()
        || !PolicyCut.hasTransformation(entry)) {
      return List.of();
    }
    Statement declaration =
        entry.statements().stream()
            .flatMap(
                span ->
                    program.statements(span.router()).stream()
                        .filter(statement -> statement.span().equals(span)))
            .filter(
                statement ->
                    statement
                        .match()
                        .map(match -> match.production().id().equals("route-map"))
                        .orElse(false))
            .findFirst()
            .orElse(null);
    if (declaration == null) {
      return List.of();
    }
    try {
      return List.of(
          Hypothesis.of(
              program.snapshot(),
              List.of(
                  new EditOperator.ReplaceSlots(
                      declaration,
                      "action",
                      List.of("permit"),
                      new Provenance.Inferred("first-match unreachable transformation")))));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return List.of();
    }
  }

  private static int sequence(Statement statement) {
    try {
      return Integer.parseInt(
          statement
              .match()
              .orElseThrow()
              .values()
              .getOrDefault("sequence", List.of(""))
              .getFirst());
    } catch (NumberFormatException malformed) {
      return Integer.MAX_VALUE;
    }
  }
}
