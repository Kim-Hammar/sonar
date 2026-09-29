package se.kth.sonar.engine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Chooses which verified improvements to adopt together, so that independent faults are repaired in
 * one step.
 *
 * <p>Candidates that improve the verdict without side effects are taken in order of how many
 * specifications they fix. A candidate joins the adoption when it changes no line that a chosen
 * candidate changes. The union is verified once and adopted if it still improves the verdict
 * without side effects; otherwise the best single candidate is adopted.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class Adoption {
  private final VerificationService verifier;

  /**
   * A candidate with its verification.
   *
   * @param hypothesis the candidate
   * @param verification the verification of its result
   */
  public record Verified(Hypothesis hypothesis, Verification verification) {}

  /** Creates an adoption step that verifies unions with {@code verifier}. */
  public Adoption(VerificationService verifier) {
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /** Returns the change to adopt in {@code state}, or nothing if no candidate improves it. */
  public Optional<Snapshot.Delta> adopt(RepairState state, List<Verified> improvements) {
    throw new NotImplemented("Adoption.adopt");
  }
}
