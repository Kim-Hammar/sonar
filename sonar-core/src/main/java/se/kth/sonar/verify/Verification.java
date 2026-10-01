package se.kth.sonar.verify;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;

/** The immutable result of verifying one snapshot. */
public final class Verification {
  private final ContentHash snapshot;
  private final Verdict verdict;
  private final DataPlaneEvidence evidence;
  private final ContentHash digest;

  /** Creates a result from the snapshot identifier, specification verdict, and data-plane facts. */
  public Verification(ContentHash snapshot, Verdict verdict, DataPlaneEvidence evidence) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.verdict = Objects.requireNonNull(verdict, "verdict");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.digest = computeDigest(snapshot, verdict, evidence);
  }

  /** Returns the identifier of the verified snapshot. */
  public ContentHash snapshot() {
    return snapshot;
  }

  /** Returns the verdict against the formal specifications. */
  public Verdict verdict() {
    return verdict;
  }

  /** Returns the data-plane facts collected during verification. */
  public DataPlaneEvidence evidence() {
    return evidence;
  }

  /** Returns a digest of the snapshot, verdict, and evidence for dependent analysis caches. */
  public ContentHash digest() {
    return digest;
  }

  private static ContentHash computeDigest(
      ContentHash snapshot, Verdict verdict, DataPlaneEvidence evidence) {
    ContentHash.Builder digest = ContentHash.builder().add(snapshot);
    for (List<Specification> group :
        List.of(verdict.unresolved(), verdict.fixed(), verdict.sideEffects())) {
      digest.add(group.size());
      group.forEach(specification -> digest.add(specification.predicate().key()));
    }
    digest.add(evidence.digest());
    return digest.build();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Verification that
        && snapshot.equals(that.snapshot)
        && verdict.equals(that.verdict)
        && evidence.equals(that.evidence);
  }

  @Override
  public int hashCode() {
    return Objects.hash(snapshot, verdict, evidence);
  }

  @Override
  public String toString() {
    return "Verification[snapshot="
        + snapshot
        + ", verdict="
        + verdict
        + ", evidence="
        + evidence
        + "]";
  }
}
