package se.kth.sonar.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

class SearchSpaceTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final Specification A = target("10.0.0.0/24");
  private static final Specification B = target("10.0.1.0/24");

  private static Specification target(String prefix) {
    return new Specification(
        new ForwardingPredicate.Reachability(R1, Prefix.parse(prefix)),
        Specification.Status.parse("broken_removed"));
  }

  private static SiteRegion region(String id, int bound, Specification... targets) {
    return new SiteRegion(
        new Region.Id(id),
        new ProgramObject.Ref("interface", R1, List.of(id)),
        List.of(targets),
        Map.of(),
        new Region.Hypotheses.Deferred(bound));
  }

  private static List<List<String>> layout(SearchSpace space) {
    return space.partitions().stream()
        .map(partition -> partition.regions().stream().map(Region.Id::value).toList())
        .toList();
  }

  @Test
  void partitionsGroupRegionsBySignatureWithinTheCapacity() {
    SearchSpace space =
        SearchSpace.partitioned(
            List.of(
                region("r1", 10, A),
                region("r2", 10, A),
                region("r3", 10, B),
                region("r4", 10, A),
                region("r5", 10, A)),
            new SearchSpace.Capacity(2, 100));
    assertThat(layout(space))
        .containsExactly(List.of("r1", "r2"), List.of("r4", "r5"), List.of("r3"));
    assertThat(space.partitionOf(new Region.Id("r4"))).isEqualTo(space.partitions().get(1));
    assertThat(space.size()).isEqualTo(5);
  }

  @Test
  void theHypothesisCapacitySplitsPartitionsAndOversizedRegionsStandAlone() {
    SearchSpace space =
        SearchSpace.partitioned(
            List.of(
                region("r1", 10, A), region("r2", 10, A), region("r3", 10, A), region("r4", 40, A)),
            new SearchSpace.Capacity(10, 25));
    assertThat(layout(space)).containsExactly(List.of("r1", "r2"), List.of("r3"), List.of("r4"));
  }

  @Test
  void theSignatureIgnoresTheOrderOfTargets() {
    assertThat(region("x", 1, A, B).signature()).isEqualTo(region("y", 1, B, A).signature());
    assertThat(region("x", 1, A).signature()).isNotEqualTo(region("y", 1, B).signature());
  }

  @Test
  void explicitPartitionsMustKeepTheInvariants() {
    SiteRegion r1 = region("r1", 10, A);
    SiteRegion r2 = region("r2", 10, A);
    SiteRegion other = region("r3", 10, B);
    ContentHash signature = r1.signature();
    SearchSpace.Capacity capacity = new SearchSpace.Capacity(2, 100);
    Partition both = new Partition(new Partition.Id("p"), signature, List.of(r1.id(), r2.id()));
    assertThatThrownBy(() -> SearchSpace.of(List.of(r1, r2, other), List.of(both), capacity))
        .hasMessageContaining("in no partition");
    assertThatThrownBy(
            () ->
                SearchSpace.of(
                    List.of(r1, r2),
                    List.of(
                        both, new Partition(new Partition.Id("q"), signature, List.of(r1.id()))),
                    capacity))
        .hasMessageContaining("in two partitions");
    assertThatThrownBy(
            () ->
                SearchSpace.of(
                    List.of(r1, other),
                    List.of(
                        new Partition(
                            new Partition.Id("p"), signature, List.of(r1.id(), other.id()))),
                    capacity))
        .hasMessageContaining("signature");
    assertThatThrownBy(
            () -> SearchSpace.of(List.of(r1, r2), List.of(both), new SearchSpace.Capacity(1, 100)))
        .hasMessageContaining("exceeds the capacity");
    assertThatThrownBy(() -> SearchSpace.of(List.of(r1), List.of(both), capacity))
        .hasMessageContaining("unknown region");
  }

  @Test
  void removingRegionsDropsEmptyPartitions() {
    SearchSpace space =
        SearchSpace.partitioned(
            List.of(region("r1", 1, A), region("r2", 1, B)), new SearchSpace.Capacity(4, 100));
    SearchSpace rest = space.without(List.of(new Region.Id("r2")));
    assertThat(layout(rest)).containsExactly(List.of("r1"));
    assertThat(rest.without(List.of(new Region.Id("r1"))).isEmpty()).isTrue();
  }

  @Test
  void anExpandedRegionReplacesTheDeferredOne() {
    SiteRegion deferred = region("r1", 5, A);
    SearchSpace space =
        SearchSpace.partitioned(List.of(deferred), new SearchSpace.Capacity(4, 100));
    Region expanded = deferred.withHypotheses(new Region.Hypotheses.Admitted(List.of()));
    SearchSpace updated = space.replace(expanded);
    assertThat(updated.region(deferred.id()).orElseThrow().bound()).isZero();
    assertThat(updated.region(deferred.id()).orElseThrow().hypotheses())
        .isInstanceOf(Region.Hypotheses.Admitted.class);
    assertThatThrownBy(() -> space.replace(region("r9", 1, A)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aRegionIsCurrentWhileTheConfigurationsItReadAreUnchanged() {
    Snapshot before = Snapshot.of(Map.of("r1.cfg", "hostname r1\n"));
    Snapshot after = Snapshot.of(Map.of("r1.cfg", "hostname r1\n!\n"));
    SiteRegion region =
        new SiteRegion(
            new Region.Id("x"),
            new ProgramObject.Ref("router", R1, List.of()),
            List.of(A),
            Map.of(R1, before.text(R1).digest()),
            new Region.Hypotheses.Deferred(1));
    assertThat(region.isCurrent(before)).isTrue();
    assertThat(region.isCurrent(after)).isFalse();
    assertThat(region.routers()).containsExactly(R1);
  }
}
