package se.kth.sonar.app.theta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ThetaEstimateTest {
  private static List<Integer> geometric(double theta, int count, long seed) {
    Random random = new Random(seed);
    double stay = Math.exp(-theta);
    List<Integer> ranks = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      int rank = 1;
      while (random.nextDouble() < stay) {
        rank++;
      }
      ranks.add(rank);
    }
    return ranks;
  }

  @Test
  void recoversTheThetaOfGeometricRanks() {
    ThetaEstimate estimate = ThetaEstimate.of(geometric(0.5, 20_000, 7), 1);
    assertThat(estimate.theta()).isCloseTo(0.5, within(0.02));
    assertThat(estimate.low()).isLessThanOrEqualTo(estimate.theta());
    assertThat(estimate.high()).isGreaterThanOrEqualTo(estimate.theta());
    assertThat(estimate.episodes()).isEqualTo(20_000);
  }

  @Test
  void matchesTheClosedFormAndItsSearchFunction() {
    ThetaEstimate estimate = ThetaEstimate.of(List.of(1, 2, 3), 1);
    assertThat(estimate.theta()).isCloseTo(Math.log(2), within(1e-12));
    assertThat(estimate.q(1)).isCloseTo(0.5, within(1e-12));
  }

  @Test
  void isInfiniteWhenEveryFirstChoiceIsValidAndRejectsBadRanks() {
    assertThat(ThetaEstimate.of(List.of(1, 1), 1).theta()).isInfinite();
    assertThatThrownBy(() -> ThetaEstimate.of(List.of(), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ThetaEstimate.of(List.of(0), 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
