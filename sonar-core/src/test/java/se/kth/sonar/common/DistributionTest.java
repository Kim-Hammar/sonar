package se.kth.sonar.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DistributionTest {
  private static Map<String, Double> weights(Object... pairs) {
    Map<String, Double> weights = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      weights.put((String) pairs[index], (Double) pairs[index + 1]);
    }
    return weights;
  }

  @Test
  void normalisesWeights() {
    Distribution<String> distribution = Distribution.of(weights("a", 1.0, "b", 3.0));
    assertThat(distribution.probability("a")).isCloseTo(0.25, within(1e-12));
    assertThat(distribution.probability("b")).isCloseTo(0.75, within(1e-12));
    assertThat(distribution.probability("c")).isZero();
    assertThat(distribution.size()).isEqualTo(2);
  }

  @Test
  void allZeroWeightsGiveTheUniformDistribution() {
    Distribution<String> distribution = Distribution.of(weights("a", 0.0, "b", 0.0));
    assertThat(distribution).isEqualTo(Distribution.uniform(List.of("a", "b")));
    assertThat(distribution.probability("a")).isEqualTo(0.5);
  }

  @Test
  void rejectsInvalidWeightsAndEmptySupport() {
    assertThatThrownBy(() -> Distribution.of(weights("a", -1.0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Distribution.of(weights("a", Double.NaN)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Distribution.of(weights("a", Double.POSITIVE_INFINITY)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Distribution.of(Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Distribution.uniform(List.of("a", "a")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rankingBreaksTiesByOriginalOrder() {
    Distribution<String> distribution = Distribution.of(weights("b", 1.0, "a", 2.0, "c", 2.0));
    assertThat(distribution.ranked()).containsExactly("a", "c", "b");
    assertThat(distribution.argmax()).isEqualTo("a");
    assertThat(distribution.outcomes()).containsExactly("b", "a", "c");
  }

  @Test
  void withoutConditionsOnTheRemainingOutcomes() {
    Distribution<String> distribution = Distribution.of(weights("a", 1.0, "b", 1.0, "c", 2.0));
    Distribution<String> rest = distribution.without(List.of("c"));
    assertThat(rest.probability("a")).isCloseTo(0.5, within(1e-12));
    assertThat(rest.probability("c")).isZero();
    assertThatThrownBy(() -> distribution.without(List.of("a", "b", "c")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void smoothingMixesWithTheUniformDistribution() {
    Distribution<String> distribution = Distribution.of(weights("a", 1.0, "b", 0.0));
    assertThat(distribution.support()).containsExactly("a");
    Distribution<String> smoothed = distribution.smoothed(0.5);
    assertThat(smoothed.probability("a")).isCloseTo(0.75, within(1e-12));
    assertThat(smoothed.probability("b")).isCloseTo(0.25, within(1e-12));
    assertThat(smoothed.support()).containsExactly("a", "b");
    assertThatThrownBy(() -> distribution.smoothed(1.5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void massSumsDistinctOutcomes() {
    Distribution<String> distribution = Distribution.of(weights("a", 1.0, "b", 1.0, "c", 2.0));
    assertThat(distribution.mass(List.of("a", "a", "c", "z"))).isCloseTo(0.75, within(1e-12));
  }
}
