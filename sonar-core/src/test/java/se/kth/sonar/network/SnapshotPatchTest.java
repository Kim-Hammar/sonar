package se.kth.sonar.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

class SnapshotPatchTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final RouterId R2 = RouterId.of("r2");
  private static final String TEXT = "a\nb\nc\nd\n";

  @Test
  void splitsLinesLikeTheConfigurationFiles() {
    assertThat(ConfigText.of("").lines()).isEmpty();
    assertThat(ConfigText.of("a\n").lines()).containsExactly("a");
    assertThat(ConfigText.of("a\n\n").lines()).containsExactly("a", "");
    assertThat(ConfigText.of("a\r\nb").lines()).containsExactly("a", "b");
    assertThat(ConfigText.of(TEXT).line(2)).isEqualTo("b");
  }

  @Test
  void appliesEditsAgainstTheOriginalLineNumbers() {
    ConfigText result =
        ConfigText.of(TEXT)
            .apply(
                List.of(
                    new LineEdit.Replace(SourceSpan.line(R1, 2), List.of("B")),
                    new LineEdit.Delete(SourceSpan.line(R1, 4)),
                    new LineEdit.InsertAfter(R1, 1, List.of("a1")),
                    new LineEdit.Append(R1, List.of("e"))));
    assertThat(result.text()).isEqualTo("a\na1\nB\nc\ne\n");
  }

  @Test
  void insertionsAtOnePositionKeepTheirOrder() {
    ConfigText result =
        ConfigText.of(TEXT)
            .apply(
                List.of(
                    new LineEdit.InsertAfter(R1, 0, List.of("x")),
                    new LineEdit.InsertAfter(R1, 0, List.of("y"))));
    assertThat(result.lines()).containsExactly("x", "y", "a", "b", "c", "d");
  }

  @Test
  void anInsertionAfterAReplacedRangeFollowsTheReplacement() {
    ConfigText result =
        ConfigText.of(TEXT)
            .apply(
                List.of(
                    new LineEdit.InsertAfter(R1, 3, List.of("y")),
                    new LineEdit.Replace(new SourceSpan(R1, 2, 3), List.of("X")),
                    new LineEdit.InsertAfter(R1, 1, List.of("w"))));
    assertThat(result.lines()).containsExactly("a", "w", "X", "y", "d");
  }

  @Test
  void keepsTheAbsenceOfATrailingLineBreak() {
    assertThat(ConfigText.of("a\nb").apply(List.of(new LineEdit.Append(R1, List.of("c")))).text())
        .isEqualTo("a\nb\nc");
  }

  @Test
  void rejectsConflictingOrOutOfRangeEdits() {
    ConfigText text = ConfigText.of(TEXT);
    assertThatThrownBy(
            () ->
                text.apply(
                    List.of(
                        new LineEdit.Delete(new SourceSpan(R1, 2, 3)),
                        new LineEdit.Replace(SourceSpan.line(R1, 3), List.of("x")))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> text.apply(List.of(new LineEdit.Delete(SourceSpan.line(R1, 5)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                text.apply(
                    List.of(
                        new LineEdit.Delete(new SourceSpan(R1, 2, 3)),
                        new LineEdit.InsertAfter(R1, 2, List.of("x")))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> text.apply(List.of(new LineEdit.InsertAfter(R1, 5, List.of("x")))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LineEdit.Append(R1, List.of("a\nb")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noEditsReturnTheSameText() {
    ConfigText text = ConfigText.of(TEXT);
    assertThat(text.apply(List.of())).isSameAs(text);
  }

  @Test
  void patchingSharesUntouchedConfigurations() {
    Snapshot base = Snapshot.of(Map.of("R1.cfg", TEXT, "R2.cfg", "x\n"));
    Snapshot.Delta delta =
        base.patch(Transaction.of(base, List.of(new LineEdit.Delete(SourceSpan.line(R1, 1)))));
    assertThat(delta.changed()).containsExactly(R1);
    assertThat(delta.after().text(R2)).isSameAs(base.text(R2));
    assertThat(delta.after().text(R1).text()).isEqualTo("b\nc\nd\n");
    assertThat(delta.after().fileName(R1)).isEqualTo("R1.cfg");
    assertThat(delta.after().id()).isNotEqualTo(base.id());
  }

  @Test
  void patchingRejectsATransactionBuiltForAnotherVersion() {
    Snapshot base = Snapshot.of(Map.of("R1.cfg", TEXT));
    Transaction transaction =
        Transaction.of(base, List.of(new LineEdit.Delete(SourceSpan.line(R1, 1))));
    Snapshot after = base.patch(transaction).after();
    assertThatThrownBy(() -> after.patch(transaction)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aTransactionThatChangesNothingKeepsTheSnapshot() {
    Snapshot base = Snapshot.of(Map.of("R1.cfg", TEXT));
    Snapshot.Delta delta =
        base.patch(
            Transaction.of(
                base, List.of(new LineEdit.Replace(SourceSpan.line(R1, 1), List.of("a")))));
    assertThat(delta.changesNothing()).isTrue();
    assertThat(delta.after()).isSameAs(base);
  }

  @Test
  void transactionsRejectUnknownRoutersAndEmptyEdits() {
    Snapshot base = Snapshot.of(Map.of("R1.cfg", TEXT));
    assertThatThrownBy(
            () -> Transaction.of(base, List.of(new LineEdit.Delete(SourceSpan.line(R2, 1)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Transaction.of(base, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
