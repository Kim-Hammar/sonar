package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.text.RouterId;

/**
 * Extends an identity rename through the program's definition-use graph.
 *
 * <p>When two loopbacks have the same address, their BGP neighbor objects collapse onto one key. A
 * candidate that gives one loopback its declared router identity must therefore split the uses as
 * one atomic refactoring: local protocol identities follow the definition, a peer on the other
 * owner cannot be a self-session, and one of every duplicated use on the remaining routers follows
 * the renamed definition. All edits are derived from typed statements; no address or router is
 * named by this rule.
 */
public final class IdentityRefactoring {
  private IdentityRefactoring() {}

  /** Returns dependency-closed variants of hypotheses that rename a colliding loopback address. */
  public static List<Hypothesis> complete(
      ProgramModel program, List<? extends Hypothesis> hypotheses) {
    List<Hypothesis> completed = new ArrayList<>();
    for (Hypothesis hypothesis : hypotheses) {
      identityChange(program, hypothesis)
          .flatMap(change -> complete(program, hypothesis, change))
          .ifPresent(completed::add);
    }
    return completed;
  }

  private record Change(RouterId router, String before, String after, Provenance provenance) {}

  private static Optional<Change> identityChange(ProgramModel program, Hypothesis hypothesis) {
    for (EditOperator operator : hypothesis.operators()) {
      if (!(operator instanceof EditOperator.ReplaceSlots replace)
          || !replace.statement().match().orElseThrow().production().id().equals("ip-address")) {
        continue;
      }
      List<String> after = replace.values().get("address");
      List<String> before = replace.statement().match().orElseThrow().values("address");
      if (after == null || after.size() != 1 || before.size() != 1) {
        continue;
      }
      boolean loopback =
          program.objects("interface").stream()
              .filter(object -> object.ref().router().equals(operator.router()))
              .filter(object -> object.ref().key().equals(List.of("Loopback0")))
              .anyMatch(object -> object.statements().contains(replace.statement().span()));
      long owners =
          program.objects("interface").stream()
              .filter(object -> object.values("address").equals(before))
              .count();
      if (loopback && owners > 1 && !before.equals(after)) {
        return Optional.of(
            new Change(
                operator.router(), before.getFirst(), after.getFirst(), operator.provenance()));
      }
    }
    return Optional.empty();
  }

  private static Optional<Hypothesis> complete(
      ProgramModel program, Hypothesis hypothesis, Change change) {
    List<EditOperator> operators = new ArrayList<>(hypothesis.operators());
    localReferences(program, change, operators);
    neighborReferences(program, change, operators);
    if (operators.size() == hypothesis.operators().size()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Hypothesis.of(program.snapshot(), operators));
    } catch (IllegalArgumentException conflict) {
      return Optional.empty();
    }
  }

  /** Keeps protocol identifiers and exact host coverage tied to the renamed local definition. */
  private static void localReferences(
      ProgramModel program, Change change, List<EditOperator> operators) {
    Set<String> identityProductions = Set.of("bgp-router-id", "ospf-router-id", "ospf-network");
    for (Statement statement : program.statements(change.router())) {
      Production.Match match = statement.match().orElse(null);
      if (match == null || !identityProductions.contains(match.production().id())) {
        continue;
      }
      for (String slot : List.of("id", "network")) {
        if (match.production().slot(slot).isPresent()
            && match.values(slot).equals(List.of(change.before()))
            && (slot.equals("id") || match.values("wildcard").equals(List.of("0.0.0.0")))) {
          operators.add(
              new EditOperator.ReplaceSlots(
                  statement, slot, List.of(change.after()), change.provenance()));
        }
      }
    }
  }

  /** Splits collapsed BGP neighbor definitions into one typed use per loopback owner. */
  private static void neighborReferences(
      ProgramModel program, Change change, List<EditOperator> operators) {
    List<RouterId> owners =
        program.objects("interface").stream()
            .filter(object -> object.values("address").equals(List.of(change.before())))
            .map(object -> object.ref().router())
            .distinct()
            .toList();
    if (owners.size() < 2) {
      return;
    }
    String designAs =
        program
            .object(new ProgramObject.Ref(ObjectType.ROUTER, change.router(), List.of()))
            .flatMap(router -> router.value("design-asn"))
            .orElse("");
    Set<Production> neighborProductions =
        new HashSet<>(program.schema().object("bgp-neighbor").orElseThrow().sources());
    for (RouterId router : program.snapshot().routers()) {
      if (router.equals(change.router()) || !sameDesignAs(program, router, designAs)) {
        continue;
      }
      Map<String, List<Statement>> groups = new LinkedHashMap<>();
      for (Statement statement : program.statements(router)) {
        Production.Match match = statement.match().orElse(null);
        if (match == null
            || !neighborProductions.contains(match.production())
            || !match.values("peer").equals(List.of(change.before()))) {
          continue;
        }
        Map<String, List<String>> values = new HashMap<>(match.values());
        values.remove("peer");
        String key = match.production().id() + "|" + values;
        groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(statement);
      }
      boolean otherOwner = owners.contains(router);
      for (List<Statement> group : groups.values()) {
        if (otherOwner || group.size() >= owners.size()) {
          operators.add(
              new EditOperator.ReplaceSlots(
                  group.getLast(), "peer", List.of(change.after()), change.provenance()));
        }
      }
    }
  }

  private static boolean sameDesignAs(ProgramModel program, RouterId router, String expected) {
    if (expected.isEmpty()) {
      return false;
    }
    return program
        .object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()))
        .flatMap(object -> object.value("design-asn"))
        .filter(expected::equals)
        .isPresent();
  }
}
