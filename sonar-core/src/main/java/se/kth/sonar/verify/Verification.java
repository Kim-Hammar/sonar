package se.kth.sonar.verify;

import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.spec.Verdict;

/**
 * The result of verifying one snapshot.
 *
 * @param snapshot the identifier of the verified snapshot
 * @param verdict the verdict against the specifications
 * @param evidence data-plane facts about the violated specifications
 */
public record Verification(ContentHash snapshot, Verdict verdict, DataPlaneEvidence evidence) {
  /** Checks the components. */
  public Verification {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(verdict, "verdict");
    Objects.requireNonNull(evidence, "evidence");
  }
}
