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
