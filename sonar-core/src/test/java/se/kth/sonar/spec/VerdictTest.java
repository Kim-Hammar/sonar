package se.kth.sonar.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.text.RouterId;

class VerdictTest {
  private static final Specification A = broken("10.0.0.0/24");
  private static final Specification B = broken("10.0.1.0/24");
  private static final Specification C = broken("10.0.2.0/24");
  private static final Specification INTACT =
      new Specification(
          new ForwardingPredicate.Reachability(RouterId.of("r1"), Prefix.parse("10.0.9.0/24")),
          Specification.Status.parse("intact"));

  private static Specification broken(String prefix) {
    return new Specification(
        new ForwardingPredicate.Reachability(RouterId.of("r1"), Prefix.parse(prefix)),
        Specification.Status.parse("broken_removed"));
  }

  private static Verdict verdict(List<Specification> unresolved, List<Specification> sideEffects) {
    return new Verdict(unresolved, List.of(), sideEffects);
  }

  @Test
  void solvedMeansNothingUnresolvedAndNoSideEffects() {
    assertThat(verdict(List.of(), List.of()).solved()).isTrue();
    assertThat(verdict(List.of(A), List.of()).solved()).isFalse();
    assertThat(verdict(List.of(), List.of(INTACT)).solved()).isFalse();
  }

  @Test
  void improvingUsesStrictFormalLossAndNeverAddsAnIntactRegression() {
    Verdict before = verdict(List.of(A, B), List.of());
    assertThat(verdict(List.of(A), List.of()).improvesOn(before)).isTrue();
    assertThat(verdict(List.of(A), List.of(INTACT)).improvesOn(before)).isFalse();
    assertThat(verdict(List.of(A, B), List.of()).improvesOn(before)).isFalse();
    assertThat(verdict(List.of(C), List.of()).improvesOn(before)).isTrue();

    Verdict withExistingRegression = verdict(List.of(A, B), List.of(INTACT));
    assertThat(verdict(List.of(A), List.of(INTACT)).improvesOn(withExistingRegression)).isTrue();
    assertThat(verdict(List.of(A), List.of()).improvesOn(withExistingRegression)).isTrue();
  }

  @Test
  void changesAreClassifiedByWhatWasFixedAndWhatBroke() {
    Verdict before = verdict(List.of(A, B), List.of());
    assertThat(before.changeTo(verdict(List.of(A), List.of())))
        .isEqualTo(Verdict.Change.IMPROVEMENT);
    assertThat(before.changeTo(verdict(List.of(A), List.of(INTACT))))
        .isEqualTo(Verdict.Change.MIXED);
    assertThat(before.changeTo(verdict(List.of(A, B, C), List.of())))
        .isEqualTo(Verdict.Change.REGRESSION);
    assertThat(before.changeTo(verdict(List.of(A, B), List.of())))
        .isEqualTo(Verdict.Change.NO_EFFECT);
  }
}
