package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.BgpIncidenceSet;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.Constraint;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.text.LineEdit;

/** Materialises inferred BGP incidence members through the ordinary typed IOS grammar. */
public final class BgpIncidenceGenerator {
  private static final int MAX_AGGREGATES = 8;

  private final SeedGenerator seeds;

  /** Creates the generator from the shared typed seed generator. */
  public BgpIncidenceGenerator(SeedGenerator seeds) {
    this.seeds = java.util.Objects.requireNonNull(seeds, "seeds");
  }

  /** Returns each incomplete member transaction and their atomic set closures. */
  public List<Hypothesis> generate(ProgramModel program, BgpIncidenceSet incidence) {
    Requirement existsRemote =
        program.schema().relation("bgp-session").orElseThrow().requirements().stream()
            .filter(
                requirement ->
                    requirement.constraint() instanceof Constraint.Exists exists
                        && exists.path().attributes().isEmpty())
            .findFirst()
            .orElseThrow();
    Provenance provenance =
        new Provenance.Forced("bgp-session", existsRemote, ValueSupport.EVIDENCED);
    Map<ProgramObject.Ref, List<BgpIncidenceSet.Direction>> byClient = new LinkedHashMap<>();
    for (BgpIncidenceSet.Direction direction : incidence.directions()) {
      ProgramObject.Ref client =
          direction.source().equals(incidence.reflector())
              ? direction.target()
              : direction.source();
      byClient.computeIfAbsent(client, ignored -> new ArrayList<>()).add(direction);
    }
    List<List<Hypothesis>> members = new ArrayList<>();
    List<Hypothesis> result = new ArrayList<>();
    for (ProgramObject.Ref client : incidence.clients()) {
      List<List<EditOperator>> alternatives = List.of(List.of());
      for (BgpIncidenceSet.Direction direction : byClient.getOrDefault(client, List.of())) {
        List<List<EditOperator>> directional = new ArrayList<>();
        for (BgpIncidenceSet.Group group : direction.groups()) {
          List<EditOperator> operators = operators(program, direction, group, provenance);
          if (!operators.isEmpty()) {
            directional.add(operators);
          }
        }
        alternatives = product(alternatives, directional);
      }
      List<Hypothesis> hypotheses = new ArrayList<>();
      for (List<EditOperator> operators : alternatives) {
        hypothesis(program, operators).ifPresent(hypotheses::add);
      }
      if (!hypotheses.isEmpty()) {
        members.add(hypotheses);
        result.addAll(hypotheses);
      }
    }
    aggregate(program, members, result);
    return List.copyOf(result);
  }

  private List<EditOperator> operators(
      ProgramModel program,
      BgpIncidenceSet.Direction direction,
      BgpIncidenceSet.Group group,
      Provenance provenance) {
    ProgramObject source = program.object(direction.source()).orElse(null);
    if (source == null) {
      return List.of();
    }
    if (!source.has("bgp-asn")) {
      return process(program, source, direction, group);
    }
    ProgramObject.Ref neighborRef =
        new ProgramObject.Ref(
            "bgp-neighbor", direction.source().router(), List.of(direction.peer()));
    ProgramObject neighbor = program.object(neighborRef).orElse(null);
    ProgramObject.Ref template = neighbor == null ? group.template() : neighborRef;
    List<EditOperator> operators = new ArrayList<>();
    operators.addAll(groupDefinition(program, source, group, provenance));
    String assigned =
        neighbor == null
            ? ""
            : neighbor.value("peer-group").flatMap(BgpIncidenceGenerator::referenceKey).orElse("");
    if (!assigned.equals(group.name())) {
      EditOperator replacement =
          neighbor == null
              ? null
              : replacement(
                  program,
                  neighbor,
                  "neighbor-peer-group",
                  Map.of("group", List.of(group.name())),
                  provenance);
      if (replacement != null) {
        operators.add(replacement);
      } else {
        operators.addAll(
            seeds.insert(
                program,
                template,
                "neighbor-peer-group",
                Map.of("peer", List.of(direction.peer()), "group", List.of(group.name())),
                provenance));
      }
    }
    if (neighbor == null || !neighbor.has("activated")) {
      operators.addAll(
          seeds.insert(
              program,
              template,
              "neighbor-activate",
              Map.of("peer", List.of(direction.peer())),
              provenance));
    }
    if (group.nextHopSelf() && (neighbor == null || !neighbor.has("next-hop-self"))) {
      operators.addAll(
          seeds.insert(
              program,
              template,
              "neighbor-next-hop-self",
              Map.of("peer", List.of(direction.peer())),
              provenance));
    }
    if (neighbor != null && neighbor.has("shutdown")) {
      for (var span : neighbor.statements()) {
        program.statements(span.router()).stream()
            .filter(statement -> statement.span().equals(span))
            .filter(
                statement ->
                    statement
                        .match()
                        .map(m -> m.production().id())
                        .orElse("")
                        .equals("neighbor-shutdown"))
            .findFirst()
            .ifPresent(statement -> operators.add(new EditOperator.DeleteStatement(statement)));
      }
    }
    return unique(operators);
  }

  /** Materialises an inherited same-AS peer-group convention when it is absent locally. */
  private List<EditOperator> groupDefinition(
      ProgramModel program,
      ProgramObject source,
      BgpIncidenceSet.Group group,
      Provenance provenance) {
    ProgramObject.Ref groupRef =
        new ProgramObject.Ref("peer-group", source.ref().router(), List.of(group.name()));
    if (program.object(groupRef).isPresent()) {
      return List.of();
    }
    String asn = source.value("bgp-asn").orElse("");
    if (asn.isEmpty()) {
      return List.of();
    }
    EditOperator.InsertStatement anchor =
        seeds
            .insert(
                program,
                source.ref(),
                "peer-group",
                Map.of("group", List.of(group.name())),
                provenance)
            .stream()
            .filter(EditOperator.InsertStatement.class::isInstance)
            .map(EditOperator.InsertStatement.class::cast)
            .findFirst()
            .orElse(null);
    if (anchor == null) {
      return List.of();
    }
    return List.of(
        new EditOperator.InsertBlock(
            source.ref().router(),
            anchor.after(),
            List.of(
                line(
                    program,
                    "peer-group",
                    anchor.indentation(),
                    Map.of("group", List.of(group.name()))),
                line(
                    program,
                    "peer-group-remote-as",
                    anchor.indentation(),
                    Map.of("group", List.of(group.name()), "asn", List.of(asn))),
                line(
                    program,
                    "peer-group-update-source",
                    anchor.indentation(),
                    Map.of(
                        "group", List.of(group.name()),
                        "interface", List.of("Loopback0")))),
            provenance));
  }

  /**
   * Materialises one missing process and its observed same-AS incidence as a single typed block.
   */
  private static List<EditOperator> process(
      ProgramModel program,
      ProgramObject source,
      BgpIncidenceSet.Direction direction,
      BgpIncidenceSet.Group group) {
    String asn = source.value("design-asn").orElse("");
    String identity = source.value("loopback-address").orElse("");
    if (asn.isEmpty() || identity.isEmpty()) {
      return List.of();
    }
    Provenance provenance = new Provenance.Inferred("formal waypoint BGP process incidence");
    List<EditOperator.RenderedStatement> statements = new ArrayList<>();
    statements.add(line(program, "router-bgp", "", Map.of("asn", List.of(asn))));
    statements.add(line(program, "bgp-router-id", " ", Map.of("id", List.of(identity))));
    statements.add(line(program, "bgp-log-neighbor-changes", " ", Map.of()));
    statements.add(line(program, "no-bgp-default-ipv4", " ", Map.of()));
    statements.add(line(program, "peer-group", " ", Map.of("group", List.of(group.name()))));
    statements.add(
        line(
            program,
            "peer-group-remote-as",
            " ",
            Map.of("group", List.of(group.name()), "asn", List.of(asn))));
    statements.add(
        line(
            program,
            "peer-group-update-source",
            " ",
            Map.of("group", List.of(group.name()), "interface", List.of("Loopback0"))));
    statements.add(
        line(
            program,
            "neighbor-peer-group",
            " ",
            Map.of("peer", List.of(direction.peer()), "group", List.of(group.name()))));
    statements.add(line(program, "address-family", " ", Map.of()));
    statements.add(
        line(program, "neighbor-activate", "  ", Map.of("peer", List.of(direction.peer()))));
    if (group.nextHopSelf()) {
      statements.add(
          line(program, "neighbor-next-hop-self", "  ", Map.of("peer", List.of(direction.peer()))));
    }
    statements.add(line(program, "exit-address-family", " ", Map.of()));
    return List.of(
        new EditOperator.InsertBlock(
            source.ref().router(),
            program.snapshot().text(source.ref().router()).lineCount(),
            statements,
            provenance));
  }

  private static EditOperator.RenderedStatement line(
      ProgramModel program,
      String production,
      String indentation,
      Map<String, List<String>> values) {
    return new EditOperator.RenderedStatement(
        indentation, program.schema().grammar().production(production).orElseThrow(), values);
  }

  private static EditOperator replacement(
      ProgramModel program,
      ProgramObject object,
      String production,
      Map<String, List<String>> values,
      Provenance provenance) {
    for (var span : object.statements()) {
      for (var statement : program.statements(span.router())) {
        if (statement.span().equals(span)
            && statement.match().map(m -> m.production().id()).orElse("").equals(production)) {
          return new EditOperator.ReplaceSlots(statement, values, provenance);
        }
      }
    }
    return null;
  }

  private static List<List<EditOperator>> product(
      List<List<EditOperator>> left, List<List<EditOperator>> right) {
    if (left.isEmpty() || right.isEmpty()) {
      return List.of();
    }
    List<List<EditOperator>> result = new ArrayList<>();
    for (List<EditOperator> a : left) {
      for (List<EditOperator> b : right) {
        List<EditOperator> joined = new ArrayList<>(a);
        joined.addAll(b);
        result.add(unique(joined));
        if (result.size() == MAX_AGGREGATES) {
          return result;
        }
      }
    }
    return result;
  }

  private static void aggregate(
      ProgramModel program, List<List<Hypothesis>> members, List<Hypothesis> result) {
    List<List<EditOperator>> combinations = List.of(List.of());
    for (List<Hypothesis> member : members) {
      List<List<EditOperator>> alternatives = member.stream().map(Hypothesis::operators).toList();
      combinations = product(combinations, alternatives);
    }
    for (List<EditOperator> operators : combinations) {
      hypothesis(program, operators).ifPresent(result::add);
    }
  }

  private static java.util.Optional<Hypothesis> hypothesis(
      ProgramModel program, List<EditOperator> operators) {
    List<EditOperator> unique = unique(operators);
    if (unique.isEmpty()) {
      return java.util.Optional.empty();
    }
    List<LineEdit> edits = unique.stream().map(EditOperator::lower).toList();
    try {
      return java.util.Optional.of(
          new Hypothesis(Transaction.of(program.snapshot(), edits), unique));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return java.util.Optional.empty();
    }
  }

  private static List<EditOperator> unique(List<EditOperator> operators) {
    return List.copyOf(new LinkedHashSet<>(operators));
  }

  private static java.util.Optional<String> referenceKey(String text) {
    if (!text.contains(":")) {
      return text.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(text);
    }
    try {
      ProgramObject.Ref ref = ProgramObject.Ref.parse(text);
      return ref.key().size() == 1
          ? java.util.Optional.of(ref.key().getFirst())
          : java.util.Optional.empty();
    } catch (IllegalArgumentException malformed) {
      return java.util.Optional.empty();
    }
  }
}
