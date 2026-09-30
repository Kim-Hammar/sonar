package se.kth.sonar.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.search.Region;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

class TransitionLedgerTest {
  private static final RouterId R1 = RouterId.of("r1");

  private static Hypothesis hypothesis(Snapshot base, String line) {
    Transaction transaction =
        Transaction.of(
            base, List.of(new LineEdit.Replace(new SourceSpan(R1, 1, 1), List.of(line))));
    return new Hypothesis(transaction, List.of());
  }

  @Test
  void remembersWhatWasTriedUnderWhichDependency() {
    Snapshot base = Snapshot.of(Map.of("r1.cfg", "hostname r1\n"));
    Hypothesis first = hypothesis(base, "hostname a");
    Hypothesis second = hypothesis(base, "hostname b");
    Verification verification =
        new Verification(
            base.id(), new Verdict(List.of(), List.of(), List.of()), DataPlaneEvidence.none());
    TransitionLedger ledger = new TransitionLedger();
    ContentHash before = ContentHash.of("forwarding before");
    ContentHash after = ContentHash.of("forwarding after");
    ledger.add(
        new TransitionLedger.Transition(
            0, new Region.Id("a"), first, verification, Verdict.Change.NO_EFFECT, before));
    assertThat(ledger.tried(first, before)).isTrue();
    assertThat(ledger.tried(first, after)).isFalse();
    assertThat(ledger.tried(second, before)).isFalse();
    assertThat(ledger.triedIn(0)).hasSize(1);
    assertThat(ledger.triedIn(1)).isEmpty();
    assertThat(ledger.verificationOf(base.id())).contains(verification);
    assertThat(ledger.size()).isEqualTo(1);
  }
}
