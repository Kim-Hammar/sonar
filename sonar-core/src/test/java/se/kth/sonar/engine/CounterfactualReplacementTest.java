package se.kth.sonar.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

class CounterfactualReplacementTest {
  private static final RouterId ONE = RouterId.of("1_as65001_r1");
  private static final RouterId TWO = RouterId.of("2_as65001_r2");
  private static final RouterId THREE = RouterId.of("3_as65001_r3");

  @Test
  void replaysIndependentLaterAdoptionsWhileOmittingOneEarlierChoice() {
    Snapshot initial =
        Snapshot.of(
            Map.of(
                ONE + ".cfg", "hostname r1\nvalue a\n",
                TWO + ".cfg", "hostname r2\nvalue b\n",
                THREE + ".cfg", "hostname r3\nvalue c\n"));
    Transaction first =
        Transaction.of(
            initial,
            List.of(new LineEdit.Replace(new SourceSpan(ONE, 2, 2), List.of("value first"))));
    Snapshot afterFirst = initial.patch(first).after();
    Transaction second =
        Transaction.of(
            afterFirst,
            List.of(new LineEdit.Replace(new SourceSpan(TWO, 2, 2), List.of("value second"))));
    Snapshot current = afterFirst.patch(second).after();
    Transaction candidate =
        Transaction.of(
            current,
            List.of(new LineEdit.Replace(new SourceSpan(THREE, 2, 2), List.of("value candidate"))));

    List<CounterfactualReplacement.Alternative> alternatives =
        CounterfactualReplacement.alternatives(
            initial, List.of(first, second), new Hypothesis(candidate, List.of()));

    assertThat(alternatives)
        .extracting(CounterfactualReplacement.Alternative::replaced)
        .containsExactly(1, 0);
    assertThat(alternatives.get(1).snapshot().text(ONE).text()).contains("value a");
    assertThat(alternatives.get(1).snapshot().text(TWO).text()).contains("value second");
    assertThat(alternatives.get(1).snapshot().text(THREE).text()).contains("value candidate");
    assertThat(replay(initial, alternatives.get(1).history()))
        .isEqualTo(alternatives.get(1).snapshot());
  }

  @Test
  void rebasesDisjointChangesOnTheSameRouter() {
    Snapshot initial =
        Snapshot.of(
            Map.of(
                ONE + ".cfg",
                """
                hostname r1
                first old
                second old
                third old
                """));
    Transaction first =
        Transaction.of(
            initial,
            List.of(new LineEdit.Replace(new SourceSpan(ONE, 2, 2), List.of("first adopted"))));
    Snapshot afterFirst = initial.patch(first).after();
    Transaction second =
        Transaction.of(
            afterFirst,
            List.of(new LineEdit.Replace(new SourceSpan(ONE, 3, 3), List.of("second adopted"))));
    Snapshot current = afterFirst.patch(second).after();
    Transaction candidate =
        Transaction.of(
            current,
            List.of(new LineEdit.Replace(new SourceSpan(ONE, 4, 4), List.of("third candidate"))));

    List<CounterfactualReplacement.Alternative> alternatives =
        CounterfactualReplacement.alternatives(
            initial, List.of(first, second), new Hypothesis(candidate, List.of()));

    assertThat(alternatives)
        .extracting(CounterfactualReplacement.Alternative::replaced)
        .containsExactly(1, 0);
    assertThat(alternatives.get(1).snapshot().text(ONE).text())
        .contains("first old")
        .contains("second adopted")
        .contains("third candidate");
    assertThat(replay(initial, alternatives.get(1).history()))
        .isEqualTo(alternatives.get(1).snapshot());
  }

  private static Snapshot replay(Snapshot initial, List<Transaction> history) {
    Snapshot snapshot = initial;
    for (Transaction transaction : history) {
      snapshot = snapshot.patch(transaction).after();
    }
    return snapshot;
  }
}
