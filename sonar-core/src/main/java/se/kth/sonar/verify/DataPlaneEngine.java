package se.kth.sonar.verify;

import java.util.Objects;
import java.util.Set;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.SpecificationSet;

/**
 * Computes the data plane of a snapshot and reports which specified predicates hold.
 *
 * <p>An engine is used by one thread at a time. {@link VerificationService} runs several engines in
 * parallel by leasing them from a pool.
 */
public interface DataPlaneEngine extends AutoCloseable {
  /**
   * Analyses {@code snapshot} and reports which predicates of {@code specifications} hold, with
   * evidence for the violated ones.
   */
  Observation analyze(Snapshot snapshot, SpecificationSet specifications);

  /** Releases the engine's resources. */
  @Override
  void close();

  /**
   * What one analysis observed.
   *
   * @param holding the specified predicates that hold in the data plane
   * @param evidence facts that explain violated specifications
   */
  record Observation(Set<ForwardingPredicate> holding, DataPlaneEvidence evidence) {
    /** Copies the set and checks the evidence. */
    public Observation {
      holding = Set.copyOf(holding);
      Objects.requireNonNull(evidence, "evidence");
    }
  }
}
