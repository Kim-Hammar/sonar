package se.kth.sonar.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import se.kth.sonar.common.ContentHash;

/**
 * The regions a repair run searches, grouped into partitions.
 *
 * <p>Every region belongs to exactly one partition, all regions of a partition share a signature,
 * and every partition respects the capacity. A region whose bound alone exceeds the capacity forms
 * a partition of its own.
 */
public final class SearchSpace {
  private final SequencedMap<Region.Id, Region> regions;
  private final List<Partition> partitions;
  private final Map<Region.Id, Partition> partitionOf;
  private final Capacity capacity;

  private SearchSpace(
      SequencedMap<Region.Id, Region> regions,
      List<Partition> partitions,
      Map<Region.Id, Partition> partitionOf,
      Capacity capacity) {
    this.regions = regions;
    this.partitions = partitions;
    this.partitionOf = partitionOf;
    this.capacity = capacity;
  }

  /**
   * Creates a search space from explicit partitions.
   *
   * @throws IllegalArgumentException if an identifier repeats, a partition names an unknown region,
   *     a region is in no partition or in two, a partition mixes signatures, or a partition exceeds
   *     the capacity
   */
  public static SearchSpace of(
      List<? extends Region> regions, List<Partition> partitions, Capacity capacity) {
    SequencedMap<Region.Id, Region> byId = new LinkedHashMap<>();
    for (Region region : regions) {
      if (byId.put(region.id(), region) != null) {
        throw new IllegalArgumentException("region " + region.id() + " appears twice");
      }
    }
    Map<Region.Id, Partition> partitionOf = new HashMap<>();
    Set<Partition.Id> partitionIds = new HashSet<>();
    for (Partition partition : partitions) {
      if (!partitionIds.add(partition.id())) {
        throw new IllegalArgumentException("partition " + partition.id() + " appears twice");
      }
      int load = 0;
      for (Region.Id id : partition.regions()) {
        Region region = byId.get(id);
        if (region == null) {
          throw new IllegalArgumentException(
              "partition " + partition.id() + " names unknown region " + id);
        }
        if (partitionOf.put(id, partition) != null) {
          throw new IllegalArgumentException("region " + id + " is in two partitions");
        }
        if (!region.signature().equals(partition.signature())) {
          throw new IllegalArgumentException(
              "region " + id + " does not share the signature of " + partition.id());
        }
        load += region.bound();
      }
      int size = partition.regions().size();
      if (size > capacity.regions() || (size > 1 && load > capacity.hypotheses())) {
        throw new IllegalArgumentException(
            "partition " + partition.id() + " exceeds the capacity " + capacity);
      }
    }
    if (partitionOf.size() != byId.size()) {
      Set<Region.Id> missing = new HashSet<>(byId.keySet());
      missing.removeAll(partitionOf.keySet());
      throw new IllegalArgumentException("regions " + missing + " are in no partition");
    }
    return new SearchSpace(byId, List.copyOf(partitions), Map.copyOf(partitionOf), capacity);
  }

  /**
   * Groups regions by signature, in order of first appearance, and fills the partitions of each
   * group in region order up to the capacity.
   */
  public static SearchSpace partitioned(List<? extends Region> regions, Capacity capacity) {
    SequencedMap<ContentHash, List<Region>> groups = new LinkedHashMap<>();
    for (Region region : regions) {
      groups.computeIfAbsent(region.signature(), signature -> new ArrayList<>()).add(region);
    }
    List<Partition> partitions = new ArrayList<>();
    groups.forEach(
        (signature, members) -> {
          List<Region.Id> current = new ArrayList<>();
          int load = 0;
          for (Region region : members) {
            boolean full =
                current.size() == capacity.regions()
                    || load + region.bound() > capacity.hypotheses();
            if (!current.isEmpty() && full) {
              partitions.add(partition(signature, partitions.size(), current));
              current = new ArrayList<>();
              load = 0;
            }
            current.add(region.id());
            load += region.bound();
          }
          partitions.add(partition(signature, partitions.size(), current));
        });
    return of(regions, partitions, capacity);
  }

  private static Partition partition(ContentHash signature, int index, List<Region.Id> members) {
    return new Partition(
        new Partition.Id(signature.abbreviated() + "-" + index), signature, members);
  }

  /** Returns the regions in order. */
  public List<Region> regions() {
    return List.copyOf(regions.values());
  }

  /** Returns the region identified by {@code id}. */
  public Optional<Region> region(Region.Id id) {
    return Optional.ofNullable(regions.get(id));
  }

  /** Returns the partitions in order. */
  public List<Partition> partitions() {
    return partitions;
  }

  /** Returns the partition that contains the region {@code id}. */
  public Partition partitionOf(Region.Id id) {
    Partition partition = partitionOf.get(id);
    if (partition == null) {
      throw new NoSuchElementException("no region " + id);
    }
    return partition;
  }

  /** Returns the capacity of a partition. */
  public Capacity capacity() {
    return capacity;
  }

  /** Returns the number of regions. */
  public int size() {
    return regions.size();
  }

  /** Returns whether there are no regions left. */
  public boolean isEmpty() {
    return regions.isEmpty();
  }

  /** Removes regions; partitions keep their other regions, and empty partitions disappear. */
  public SearchSpace without(Collection<Region.Id> removed) {
    Set<Region.Id> gone = new HashSet<>(removed);
    SequencedMap<Region.Id, Region> kept = new LinkedHashMap<>(regions);
    kept.keySet().removeAll(gone);
    List<Partition> trimmed = new ArrayList<>();
    Map<Region.Id, Partition> membership = new HashMap<>();
    for (Partition partition : partitions) {
      List<Region.Id> members =
          partition.regions().stream().filter(id -> !gone.contains(id)).toList();
      if (!members.isEmpty()) {
        Partition remaining = new Partition(partition.id(), partition.signature(), members);
        trimmed.add(remaining);
        members.forEach(id -> membership.put(id, remaining));
      }
    }
    return new SearchSpace(kept, List.copyOf(trimmed), Map.copyOf(membership), capacity);
  }

  /**
   * Replaces the region with the same identifier, for example by its expansion. The partition stays
   * as it is: the capacity bounds a partition when it is formed, and a region whose generated
   * candidates outnumber its earlier bound does not split it.
   *
   * @throws IllegalArgumentException if there is no such region or the signature differs
   */
  public SearchSpace replace(Region replacement) {
    Region current = regions.get(replacement.id());
    if (current == null) {
      throw new IllegalArgumentException("no region " + replacement.id());
    }
    if (!current.signature().equals(replacement.signature())) {
      throw new IllegalArgumentException("region " + replacement.id() + " changed its targets");
    }
    SequencedMap<Region.Id, Region> updated = new LinkedHashMap<>(regions);
    updated.put(replacement.id(), replacement);
    return new SearchSpace(updated, partitions, partitionOf, capacity);
  }

  @Override
  public String toString() {
    return "SearchSpace[" + regions.size() + " regions, " + partitions.size() + " partitions]";
  }

  /**
   * Limits on one partition, which bound the size of one decision.
   *
   * @param regions the most regions in a partition
   * @param hypotheses the most candidates in a partition, counted by region bounds
   */
  public record Capacity(int regions, int hypotheses) {
    /** Checks that both limits are positive. */
    public Capacity {
      if (regions < 1 || hypotheses < 1) {
        throw new IllegalArgumentException("capacity limits must be at least 1");
      }
    }
  }
}
