package se.kth.sonar.prior;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.RelationEvaluator;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.RelationType;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.state.RepairState;

/**
 * Weights a region by the relation requirements its candidates restore.
 *
 * <p>The instances of a relation are exchangeable observations of each of its requirements. A
 * requirement that holds in {@code s} instances and is violated in {@code v} gives the region that
 * restores it the Jeffreys posterior odds {@code (s + 1/2) / (v + 1/2)}: a requirement that holds
 * everywhere but in one place strongly implicates that place, while one violated all over the
 * network says little. A region takes the largest odds over the requirements expressed by the
 * region itself, and at least one, so no region loses support. Candidate provenance is deliberately
 * excluded: generating a deferred region is computation rather than evidence and therefore cannot
 * change its prior.
 */
public final class RelationFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;

  private final RelationEvaluator evaluator;
  private final ContentCache<Map<Restored, Tally>> tallies = new ContentCache<>(CACHE_CAPACITY);

  private record Restored(String relation, Requirement requirement) {}

  /** Creates a factor that checks requirements with {@code evaluator}. */
  public RelationFactor(RelationEvaluator evaluator) {
    this.evaluator = evaluator;
  }

  @Override
  public String name() {
    return "relation";
  }

  @Override
  public double weight(Region region, RepairState state) {
    Map<Restored, Tally> counts =
        tallies.get(state.program().snapshot().id(), () -> tallies(state.program()));
    double weight = 1;
    for (Restored restored : restored(region)) {
      Tally tally = counts.get(restored);
      if (tally != null) {
        weight = Math.max(weight, tally.odds());
      }
    }
    return weight;
  }

  /** Returns the requirements the region's candidates restore. */
  private static Set<Restored> restored(Region region) {
    Set<Restored> result = new LinkedHashSet<>();
    if (region instanceof RelationRegion relation) {
      for (Requirement requirement : relation.instance().violated()) {
        if (requirement.signals()) {
          result.add(new Restored(relation.instance().relation(), requirement));
        }
      }
    }
    if (region instanceof IncidenceRegion) {
      Requirement missingPeer =
          new Requirement(
              Requirement.Strength.SOFT,
              new se.kth.sonar.relation.Constraint.Exists(
                  new se.kth.sonar.relation.AttributePath("remote", List.of())),
              java.util.Optional.empty());
      result.add(new Restored("bgp-session", missingPeer));
    }
    return result;
  }

  /** Counts, for every requirement, the instances that satisfy it and those that violate it. */
  private Map<Restored, Tally> tallies(ProgramModel program) {
    Map<Restored, Tally> counts = new HashMap<>();
    for (RelationInstance instance : program.instances()) {
      RelationType relation = program.schema().relation(instance.relation()).orElseThrow();
      for (Requirement requirement : relation.requirements()) {
        if (!requirement.signals()) {
          continue;
        }
        Restored key = new Restored(instance.relation(), requirement);
        if (instance.violated().contains(requirement)) {
          counts.merge(key, new Tally(0, 1), RelationFactor::sum);
        } else if (evaluator.applies(program, instance, requirement)) {
          counts.merge(key, new Tally(1, 0), RelationFactor::sum);
        }
      }
    }
    return Map.copyOf(counts);
  }

  private static Tally sum(Tally left, Tally right) {
    return new Tally(left.successes() + right.successes(), left.failures() + right.failures());
  }
}
