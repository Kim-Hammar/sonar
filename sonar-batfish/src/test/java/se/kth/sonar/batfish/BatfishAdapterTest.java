package se.kth.sonar.batfish;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatfishAdapterTest {
  @Test
  void embedsTheBenchmarkBatfishBuild() {
    assertThat(BatfishInfo.version()).isEqualTo("0.36.0");
  }
}
