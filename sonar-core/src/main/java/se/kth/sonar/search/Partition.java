package se.kth.sonar.search;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;

/**
 * Regions that target the same violated specifications and are compared in one decision.
 *
 * @param id the partition's identifier
 * @param signature the signature its regions share
 * @param regions its regions, in search-space order
 */
public record Partition(Id id, ContentHash signature, List<Region.Id> regions) {
  /** Copies the regions and checks that there is at least one. */
  public Partition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(signature, "signature");
    regions = List.copyOf(regions);
    if (regions.isEmpty()) {
      throw new IllegalArgumentException("partition " + id + " has no regions");
    }
  }

  /**
   * Returns a transient decision cohort whose members are the currently strongest competitors. Its
   * identity depends only on the ordered region identifiers, so the same comparison is never paid
   * for twice in one state.
   */
  public static Partition comparison(List<Region.Id> regions) {
    ContentHash.Builder digest = ContentHash.builder().add("decision-comparison");
    regions.forEach(region -> digest.add(region.value()));
    ContentHash signature = digest.build();
    return new Partition(new Id("comparison-" + signature.abbreviated()), signature, regions);
  }

  /**
   * Identifies a partition.
   *
   * @param value the identifier text
   */
  public record Id(String value) {
    @Override
    public String toString() {
      return value;
    }
  }
}
