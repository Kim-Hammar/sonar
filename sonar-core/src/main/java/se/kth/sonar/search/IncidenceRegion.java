package se.kth.sonar.search;

import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.program.BgpIncidenceSet;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

/**
 * Candidate transactions that complete a repeated typed relation as one atomic incidence set.
 *
 * @param id the region's identifier
 * @param incidence the inferred route-reflector/client incidence set
 * @param targets the violated specifications joined to its members
 * @param reads the digest of every participating configuration
 * @param hypotheses the candidates, or a bound on their number
 */
public record IncidenceRegion(
    Region.Id id,
    BgpIncidenceSet incidence,
    List<Specification> targets,
    Map<RouterId, ContentHash> reads,
    Region.Hypotheses hypotheses)
    implements Region {

  /** Copies the collections. */
  public IncidenceRegion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(incidence, "incidence");
    targets = List.copyOf(targets);
    reads = Map.copyOf(reads);
    Objects.requireNonNull(hypotheses, "hypotheses");
  }

  @Override
  public NavigableSet<RouterId> routers() {
    return incidence.routers();
  }

  @Override
  public Region withHypotheses(Region.Hypotheses.Admitted admitted) {
    return new IncidenceRegion(id, incidence, targets, reads, admitted);
  }
}
