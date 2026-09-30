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
      aggregate.addAll(operators);
    }
    hypothesis(program, List.copyOf(new LinkedHashSet<>(aggregate)))
        .ifPresent(value -> unique.putIfAbsent(value.id(), value));
    return List.copyOf(unique.values());
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
