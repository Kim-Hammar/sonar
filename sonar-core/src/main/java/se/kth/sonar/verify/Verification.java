package se.kth.sonar.verify;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.spec.Specification;
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

  /** Returns a digest of the snapshot, verdict, and evidence for dependent analysis caches. */
  public ContentHash digest() {
    ContentHash.Builder digest = ContentHash.builder().add(snapshot);
    for (List<Specification> group :
        List.of(verdict.unresolved(), verdict.fixed(), verdict.sideEffects())) {
      digest.add(group.size());
      group.forEach(specification -> digest.add(specification.predicate().key()));
    }
    digest.add(evidence.digest());
    return digest.build();
  }
}
