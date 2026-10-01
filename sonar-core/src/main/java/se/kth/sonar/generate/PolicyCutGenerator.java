package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.state.PolicyCut;

/** Materialises exact missing permits inferred from a verifier-observed export-policy cut. */
public final class PolicyCutGenerator {
  private final SeedGenerator seeds;

  /** Creates the generator from the shared typed seed generator. */
  public PolicyCutGenerator(SeedGenerator seeds) {
    this.seeds = java.util.Objects.requireNonNull(seeds, "seeds");
  }

  /**
   * Returns one insertion per missing destination and one atomic insertion of all destinations.
   * Existing entries are never rewritten. Sequence numbers preserve IOS first-match semantics by
   * placing an insertion before a matching deny, or after the current list otherwise.
   */
  public List<Hypothesis> generate(
      ProgramModel program, ProgramObject.Ref prefixList, Set<Prefix> destinations) {
    if (!prefixList.type().equals("prefix-list") || program.object(prefixList).isEmpty()) {
      return List.of();
    }
    List<Prefix> missing =
        destinations.stream()
            .filter(
                destination ->
                    !RouteMapEvaluation.prefixListPermits(program, prefixList, destination))
            .sorted()
            .toList();
    if (missing.isEmpty()) {
      return List.of();
    }
    Map<Prefix, Integer> sequences = allocateSequences(program, prefixList, missing);
    Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    List<EditOperator> aggregate = new ArrayList<>();
    Map<Prefix, List<EditOperator>> insertions = new LinkedHashMap<>();
    for (Prefix destination : missing) {
      Integer sequence = sequences.get(destination);
      if (sequence == null) {
        continue;
      }
      List<EditOperator> operators =
          seeds.insert(
              program,
              prefixList,
              "prefix-list-entry",
              Map.of(
                  "name", prefixList.key(),
                  "sequence", List.of(sequence.toString()),
                  "action", List.of("permit"),
                  "prefix", List.of(destination.toString())),
              new Provenance.Inferred("policy-cut"));
      hypothesis(program, operators).ifPresent(value -> unique.putIfAbsent(value.id(), value));
      insertions.put(destination, operators);
      aggregate.addAll(operators);
    }
    hypothesis(program, List.copyOf(new LinkedHashSet<>(aggregate)))
        .ifPresent(value -> unique.putIfAbsent(value.id(), value));
    for (ProgramObject gate : transformationGates(program, prefixList)) {
      se.kth.sonar.grammar.Statement opening = openingStatement(program, gate);
      if (opening == null) {
        continue;
      }
      EditOperator permit =
          new EditOperator.ReplaceSlots(
              opening, "action", List.of("permit"), new Provenance.Inferred("policy-cut"));
      for (List<EditOperator> insertion : insertions.values()) {
        List<EditOperator> transaction = new ArrayList<>(insertion);
        transaction.add(permit);
        hypothesis(program, transaction).ifPresent(value -> unique.putIfAbsent(value.id(), value));
      }
      List<EditOperator> transaction = new ArrayList<>(aggregate);
      transaction.add(permit);
      hypothesis(program, List.copyOf(new LinkedHashSet<>(transaction)))
          .ifPresent(value -> unique.putIfAbsent(value.id(), value));
    }
    return List.copyOf(unique.values());
  }

  /**
   * Returns transactions that make {@code prefixList} permit all missing destinations.
   *
   * <p>Unlike {@link #generate}, this method omits partial repairs. It is used when another
   * configuration object, such as a route-map attachment, depends on the completed list and both
   * changes must be offered as one atomic hypothesis.
   */
  public List<Hypothesis> complete(
      ProgramModel program, ProgramObject.Ref prefixList, Set<Prefix> destinations) {
    List<Hypothesis> generated = generate(program, prefixList, destinations);
    if (generated.isEmpty()) {
      return List.of();
    }
    long missing =
        destinations.stream()
            .filter(
                destination ->
                    !RouteMapEvaluation.prefixListPermits(program, prefixList, destination))
            .count();
    return generated.stream()
        .filter(
            hypothesis ->
                hypothesis.operators().stream()
                        .filter(operator -> operator.shape().contains("prefix-list-entry"))
                        .count()
                    >= missing)
        .toList();
  }

  /** Returns inert deny entries that transform routes selected by {@code prefixList}. */
  private static List<ProgramObject> transformationGates(
      ProgramModel program, ProgramObject.Ref prefixList) {
    return program.objectsOf(prefixList.router()).stream()
        .filter(object -> object.ref().type().equals("route-map-entry"))
        .filter(object -> object.value("action").filter("deny"::equals).isPresent())
        .filter(PolicyCut::hasTransformation)
        .filter(
            object ->
                object.values("match-prefix-list").stream()
                    .anyMatch(value -> names(value, prefixList)))
        .toList();
  }

  private static boolean names(String value, ProgramObject.Ref target) {
    if (ProgramObject.Ref.isReference(value, "prefix-list")) {
      return ProgramObject.Ref.parse(value).equals(target);
    }
    return !target.key().isEmpty() && value.equals(target.key().getFirst());
  }

  private static se.kth.sonar.grammar.Statement openingStatement(
      ProgramModel program, ProgramObject entry) {
    for (var span : entry.statements()) {
      for (se.kth.sonar.grammar.Statement statement : program.statements(span.router())) {
        if (statement.span().equals(span)
            && statement
                .match()
                .map(match -> match.production().id())
                .orElse("")
                .equals("route-map")) {
          return statement;
        }
      }
    }
    return null;
  }

  private static Map<Prefix, Integer> allocateSequences(
      ProgramModel program, ProgramObject.Ref list, List<Prefix> destinations) {
    List<ProgramObject> entries =
        program.objectsOf(list.router()).stream()
            .filter(object -> object.ref().type().equals("prefix-list-entry"))
            .filter(object -> object.ref().key().getFirst().equals(list.key().getFirst()))
            .toList();
    Set<Integer> used = new HashSet<>();
    entries.forEach(entry -> sequence(entry).ifPresent(used::add));
    int maximum = used.stream().max(Integer::compareTo).orElse(0);
    Map<Prefix, Integer> result = new LinkedHashMap<>();
    for (Prefix destination : destinations) {
      int upper =
          entries.stream()
              .filter(entry -> entry.value("action").filter("deny"::equals).isPresent())
              .filter(entry -> RouteMapEvaluation.prefixListEntryMatches(entry, destination))
              .map(PolicyCutGenerator::sequence)
              .flatMapToInt(OptionalInt::stream)
              .min()
              .orElse(Integer.MAX_VALUE);
      Integer selected = upper == Integer.MAX_VALUE ? after(maximum, used) : before(upper, used);
      if (selected != null) {
        used.add(selected);
        maximum = Math.max(maximum, selected);
        result.put(destination, selected);
      }
    }
    return Map.copyOf(result);
  }

  private static Integer after(int maximum, Set<Integer> used) {
    if (maximum == Integer.MAX_VALUE) {
      return null;
    }
    int candidate = Math.max(1, maximum + 1);
    return used.contains(candidate) ? null : candidate;
  }

  private static Integer before(int upper, Set<Integer> used) {
    for (int candidate = upper - 1; candidate > 0; candidate--) {
      if (!used.contains(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static OptionalInt sequence(ProgramObject object) {
    try {
      return OptionalInt.of(Integer.parseInt(object.ref().key().getLast()));
    } catch (NumberFormatException malformed) {
      return OptionalInt.empty();
    }
  }

  private static java.util.Optional<Hypothesis> hypothesis(
      ProgramModel program, List<EditOperator> operators) {
    if (operators.isEmpty()) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(Hypothesis.of(program.snapshot(), operators));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return java.util.Optional.empty();
    }
  }
}
