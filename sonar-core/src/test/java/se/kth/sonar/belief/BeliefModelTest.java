package se.kth.sonar.belief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;

class BeliefModelTest {
  private static final Region.Id A = new Region.Id("a");
  private static final Region.Id B = new Region.Id("b");
  private static final Region.Id C = new Region.Id("c");
  private static final Partition AB =
      new Partition(new Partition.Id("p"), ContentHash.of("ab"), List.of(A, B));

  private final BeliefModel model = new BeliefModel();

  private static BeliefState start() {
    Map<Region.Id, Double> weights = new LinkedHashMap<>();
    weights.put(A, 1.0);
    weights.put(B, 1.0);
    weights.put(C, 2.0);
    return new BeliefModel().initial(Distribution.of(weights), 0);
  }

  @Test
  void theInitialBeliefIsThePrior() {
    BeliefState belief = start();
    assertThat(belief.posterior().probability(C)).isCloseTo(0.5, within(1e-12));
    assertThat(belief.assessed()).isEmpty();
  }

  @Test
  void anAssessmentScalesItsPartitionByTheOddsAgainstNone() {
    RegionAssessment assessment =
        new RegionAssessment(Distribution.of(Map.of(A, 0.75, B, 0.25)), 0.5);
    BeliefState belief = model.afterAssessment(start(), AB, assessment);
    assertThat(belief.isAssessed(A)).isTrue();
    assertThat(belief.isAssessed(B)).isTrue();
    assertThat(belief.odds()).containsEntry(A, 0.75).containsEntry(B, 0.25).doesNotContainKey(C);
    Distribution<Region.Id> posterior = belief.posterior();
    assertThat(posterior.probability(A) / posterior.probability(C)).isCloseTo(0.375, within(1e-12));
    assertThat(posterior.probability(A) / posterior.probability(B)).isCloseTo(3, within(1e-12));
  }

  @Test
  void aNeutralAssessmentOnlyMarksThePartition() {
    BeliefState belief = model.afterNeutralAssessment(start(), AB);
    assertThat(belief.isAssessed(A)).isTrue();
    assertThat(belief.isAssessed(B)).isTrue();
    assertThat(belief.posterior()).isEqualTo(start().posterior());
  }

  @Test
  void failedBatchesLowerSurvivalAndNeverRaiseIt() {
    BeliefState belief = model.afterFailures(start(), C, 0.5);
    assertThat(belief.posterior().probability(C)).isCloseTo(1.0 / 3, within(1e-12));
    belief = model.afterFailures(belief, C, 0.5);
    assertThat(belief.survival()).containsEntry(C, 0.25);
    belief = model.afterFailures(belief, C, 2);
    assertThat(belief.survival()).containsEntry(C, 0.0);
    assertThat(belief.posterior().probability(C)).isZero();
  }

  @Test
  void nearMissesLendBoundedSupport() {
    BeliefState belief = model.withNearMisses(start(), Map.of(A, 1, B, 3, C, 0));
    assertThat(belief.evidence()).containsEntry(A, 1.5).containsEntry(B, 1.75).doesNotContainKey(C);
    belief = model.withNearMisses(belief, Map.of());
    assertThat(belief.evidence()).isEmpty();
  }

  @Test
  void removingRegionsKeepsTheRatiosOfTheRest() {
    BeliefState belief = model.afterFailures(start(), C, 0.5).without(List.of(A));
    assertThat(belief.posterior().outcomes()).containsExactly(B, C);
    assertThat(belief.posterior().probability(B)).isCloseTo(0.5, within(1e-12));
    assertThat(belief.without(List.of(new Region.Id("absent")))).isSameAs(belief);
  }
}
