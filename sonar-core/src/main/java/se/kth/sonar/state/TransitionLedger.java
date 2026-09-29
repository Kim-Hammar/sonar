package se.kth.sonar.state;

import java.util.List;
import java.util.Optional;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.verify.Verification;

/**
 * Remembers every candidate the run has verified, by the state it was tried in. The search uses it
 * to skip candidates whose result it already knows, and reports use it to explain each step.
 */
@Placeholder
public final class TransitionLedger {
  /**
   * One verified candidate.
   *
   * @param epoch the state the candidate was tried in
   * @param transaction the candidate's edits
   * @param verification the verification of the result
   */
  public record Transition(int epoch, Transaction transaction, Verification verification) {}

  /** Adds a verified candidate. */
  public void add(Transition transition) {
    throw new NotImplemented("TransitionLedger.add");
  }

  /** Returns the verification of the snapshot {@code snapshot}, if a candidate produced it. */
  public Optional<Verification> verificationOf(ContentHash snapshot) {
    throw new NotImplemented("TransitionLedger.verificationOf");
  }

  /** Returns the candidates tried in state {@code epoch}, in the order they were verified. */
  public List<Transition> triedIn(int epoch) {
    throw new NotImplemented("TransitionLedger.triedIn");
  }
}
