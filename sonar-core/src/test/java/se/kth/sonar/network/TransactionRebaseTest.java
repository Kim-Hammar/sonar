package se.kth.sonar.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

class TransactionRebaseTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final Snapshot BASE =
      Snapshot.of(Map.of("r1.cfg", "a\nb\nc\nd\ne\n", "r2.cfg", "x\ny\n"));

  @Test
  void aChangeElsewhereOnTheRouterIsShiftedPastTheAdoptedEdit() {
    Snapshot.Delta adopted =
        BASE.patch(
            Transaction.of(BASE, List.of(new LineEdit.InsertAfter(R1, 1, List.of("a1", "a2")))));
    Transaction later =
        Transaction.of(BASE, List.of(new LineEdit.Replace(SourceSpan.line(R1, 4), List.of("D"))));
    Optional<Transaction> rebased = later.rebaseOver(adopted);
    assertThat(rebased).isPresent();
    assertThat(adopted.after().patch(rebased.get()).after().text(R1).text())
        .isEqualTo("a\na1\na2\nb\nc\nD\ne\n");
  }

  @Test
  void aChangeOnAnotherRouterIsKeptAsItIs() {
    Snapshot.Delta adopted =
        BASE.patch(Transaction.of(BASE, List.of(new LineEdit.Delete(SourceSpan.line(R1, 2)))));
    Transaction other =
        Transaction.of(
            BASE,
            List.of(new LineEdit.Replace(SourceSpan.line(RouterId.of("r2"), 1), List.of("X"))));
    assertThat(other.rebaseOver(adopted)).contains(other);
  }

  @Test
  void aChangeOfTheSameLinesCannotBeRebased() {
    Snapshot.Delta adopted =
        BASE.patch(
            Transaction.of(
                BASE, List.of(new LineEdit.Replace(SourceSpan.line(R1, 3), List.of("C")))));
    Transaction clashing =
        Transaction.of(BASE, List.of(new LineEdit.Delete(new SourceSpan(R1, 2, 3))));
    assertThat(clashing.rebaseOver(adopted)).isEmpty();
  }
}
