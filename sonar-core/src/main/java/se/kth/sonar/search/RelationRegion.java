package se.kth.sonar.search;

import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

/**
 * The candidates that move one relation instance to the state required by the verifier, possibly on
 * several routers. Most regions restore a violated program relation; a formal negative
 * counterexample can instead require suppressing an existing relation, while exchangeable program
 * evidence can require revising a jointly agreed outlier.
 *
 * @param id the region's identifier
 * @param instance the relation instance
 * @param targets the violated specifications the region is expected to repair
 * @param reads the digest of every configuration the region was derived from
 * @param goal whether candidates restore or suppress the relation
 * @param hypotheses the candidates, or a bound on their number
 */
public record RelationRegion(
    Region.Id id,
    RelationInstance instance,
    List<Specification> targets,
    Map<RouterId, ContentHash> reads,
    Goal goal,
    Region.Hypotheses hypotheses)
    implements Region {

  /** The state the verifier requires of the relation. */
  public enum Goal {
    /** Make a violated relation hold. */
    RESTORE,
    /** Materialise an optional relation whose two absent endpoints hide one another. */
    ACTIVATE,
    /** Change both ends of a satisfied agreement from an unusual value to its peer consensus. */
    REVISE,
    /** Remove a relation traversed by a formal negative counterexample. */
    SUPPRESS
  }

  /** Copies the collections. */
  public RelationRegion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(instance, "instance");
    targets = List.copyOf(targets);
    reads = Map.copyOf(reads);
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(hypotheses, "hypotheses");
  }

  /** Creates the ordinary region that restores a violated relation. */
  public RelationRegion(
      Region.Id id,
      RelationInstance instance,
      List<Specification> targets,
      Map<RouterId, ContentHash> reads,
      Region.Hypotheses hypotheses) {
    this(id, instance, targets, reads, Goal.RESTORE, hypotheses);
  }

  @Override
  public NavigableSet<RouterId> routers() {
    return instance.dependencyRouters();
  }

  @Override
  public Region withHypotheses(Region.Hypotheses.Admitted admitted) {
    return new RelationRegion(id, instance, targets, reads, goal, admitted);
  }
}
