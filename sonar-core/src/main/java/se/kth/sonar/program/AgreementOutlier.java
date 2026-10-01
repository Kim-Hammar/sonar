package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.relation.Constraint;
import se.kth.sonar.relation.RelationType;
import se.kth.sonar.relation.Requirement;

/**
 * A relation instance whose two ends agree with each other but not with its exchangeable peers.
 *
 * <p>Ordinary {@code agree} requirements detect disagreement within one relation. This analysis
 * detects the complementary case: both ends were changed to the same unusual value. It leaves the
 * target instance out, counts the values of the other instances of the same typed requirement, and
 * proposes every alternative whose Jeffreys posterior odds against the current value exceed one.
 * Verification can then distinguish several supported conventions without an arbitrary modal-value
 * tie break.
 *
 * @param instance the unusual relation instance
 * @param requirement its agreement requirement
 * @param current the value on both ends
 * @param target one better-supported alternative among peer instances
 * @param sites the objects that hold the current value
 * @param odds the leave-one-instance-out Jeffreys odds of target versus current
 */
public record AgreementOutlier(
    RelationInstance instance,
    Requirement requirement,
    List<String> current,
    List<String> target,
    Set<ProgramObject.Ref> sites,
    double odds) {
  private static final double HALF = 0.5;

  /** Copies collections and checks the observation. */
  public AgreementOutlier {
    java.util.Objects.requireNonNull(instance, "instance");
    java.util.Objects.requireNonNull(requirement, "requirement");
    current = List.copyOf(current);
    target = List.copyOf(target);
    sites = Set.copyOf(sites);
    if (current.isEmpty()
        || target.isEmpty()
        || current.equals(target)
        || sites.isEmpty()
        || odds <= 1) {
      throw new IllegalArgumentException("an agreement outlier needs a supported alternative");
    }
  }

  private record Family(String relation, Requirement requirement) {}

  private record Observation(
      RelationInstance instance,
      Requirement requirement,
      Family family,
      List<String> value,
      Set<ProgramObject.Ref> sites) {}

  /** Returns every outlier using an evaluator for {@code program}'s schema. */
  public static List<AgreementOutlier> infer(ProgramModel program) {
    return infer(program, new RelationEvaluator(program.schema()));
  }

  /** Returns every jointly agreed value that is outnumbered by an alternative peer value. */
  public static List<AgreementOutlier> infer(ProgramModel program, RelationEvaluator evaluator) {
    List<Observation> observations = new ArrayList<>();
    Map<Family, Map<List<String>, Integer>> counts = new LinkedHashMap<>();
    for (RelationInstance instance : program.instances()) {
      RelationType relation = program.schema().relation(instance.relation()).orElseThrow();
      for (Requirement requirement : relation.requirements()) {
        if (!(requirement.constraint() instanceof Constraint.Agree agree)
            || !evaluator.applies(program, instance, requirement)) {
          continue;
        }
        Optional<List<String>> left = evaluator.valueAt(program, instance, agree.left());
        Optional<List<String>> right = evaluator.valueAt(program, instance, agree.right());
        if (left.isEmpty() || left.get().isEmpty() || !left.equals(right)) {
          continue;
        }
        Set<ProgramObject.Ref> sites = new LinkedHashSet<>();
        evaluator
            .holderOf(program, instance, agree.left())
            .map(ProgramObject::ref)
            .ifPresent(sites::add);
        evaluator
            .holderOf(program, instance, agree.right())
            .map(ProgramObject::ref)
            .ifPresent(sites::add);
        if (sites.isEmpty()) {
          continue;
        }
        Family family = new Family(instance.relation(), requirement);
        List<String> value = List.copyOf(left.get());
        observations.add(new Observation(instance, requirement, family, value, Set.copyOf(sites)));
        counts.computeIfAbsent(family, ignored -> new HashMap<>()).merge(value, 1, Integer::sum);
      }
    }

    List<AgreementOutlier> result = new ArrayList<>();
    for (Observation observation : observations) {
      Map<List<String>, Integer> family = counts.get(observation.family());
      int current = family.get(observation.value()) - 1;
      List<Map.Entry<List<String>, Integer>> alternatives =
          family.entrySet().stream()
              .filter(entry -> !entry.getKey().equals(observation.value()))
              .filter(entry -> (entry.getValue() + HALF) / (current + HALF) > 1)
              .sorted(
                  Comparator.<Map.Entry<List<String>, Integer>>comparingInt(Map.Entry::getValue)
                      .reversed()
                      .thenComparing(entry -> String.join("\u0000", entry.getKey())))
              .toList();
      for (Map.Entry<List<String>, Integer> alternative : alternatives) {
        double odds = (alternative.getValue() + HALF) / (current + HALF);
        result.add(
            new AgreementOutlier(
                observation.instance(),
                observation.requirement(),
                observation.value(),
                alternative.getKey(),
                observation.sites(),
                odds));
      }
    }
    return List.copyOf(result);
  }
}
