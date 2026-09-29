package se.kth.sonar.search;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

/**
 * The candidates that edit one program object, such as one interface or one BGP neighbor.
 *
 * @param id the region's identifier
 * @param site the object
 * @param targets the violated specifications the region is expected to repair
 * @param reads the digest of every configuration the region was derived from
 * @param hypotheses the candidates, or a bound on their number
 */
public record SiteRegion(
    Region.Id id,
    ProgramObject.Ref site,
    List<Specification> targets,
    Map<RouterId, ContentHash> reads,
    Region.Hypotheses hypotheses)
    implements Region {

  /** Copies the collections. */
  public SiteRegion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(site, "site");
    targets = List.copyOf(targets);
    reads = Map.copyOf(reads);
    Objects.requireNonNull(hypotheses, "hypotheses");
  }

  @Override
  public NavigableSet<RouterId> routers() {
    return Collections.unmodifiableNavigableSet(new TreeSet<>(List.of(site.router())));
  }

  @Override
  public Region withHypotheses(Region.Hypotheses.Admitted admitted) {
    return new SiteRegion(id, site, targets, reads, admitted);
  }
}
