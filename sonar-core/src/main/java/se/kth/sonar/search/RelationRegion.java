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
 * The candidates that complete one violated relation instance, possibly on several routers, such as
 * both halves of a BGP session.
 *
 * @param id the region's identifier
 * @param instance the violated relation instance
 * @param targets the violated specifications the region is expected to repair
 * @param reads the digest of every configuration the region was derived from
 * @param hypotheses the candidates, or a bound on their number
 */
public record RelationRegion(
    Region.Id id,
    RelationInstance instance,
    List<Specification> targets,
    Map<RouterId, ContentHash> reads,
    Region.Hypotheses hypotheses)
    implements Region {

  /** Copies the collections. */
  public RelationRegion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(instance, "instance");
    targets = List.copyOf(targets);
    reads = Map.copyOf(reads);
    Objects.requireNonNull(hypotheses, "hypotheses");
  }

  @Override
  public NavigableSet<RouterId> routers() {
    return instance.routers();
  }

  @Override
  public Region withHypotheses(Region.Hypotheses.Admitted admitted) {
    return new RelationRegion(id, instance, targets, reads, admitted);
  }
}
