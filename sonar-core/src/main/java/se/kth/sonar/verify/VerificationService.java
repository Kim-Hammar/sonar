package se.kth.sonar.verify;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.SpecificationSet;

/**
 * Verifies snapshots against specifications on a pool of data-plane engines.
 *
 * <p>Results are cached by the pair of snapshot and specification identifiers, so a snapshot is
 * analysed at most once and concurrent requests for it share one analysis. {@link #verifyAll}
 * leases one engine per snapshot, runs up to the configured number of verification workers at a
 * time on virtual threads, and returns the results in input order. An engine that fails is
 * invalidated rather than returned to the pool.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class VerificationService {
  private final ResourcePool<DataPlaneEngine> engines;
  private final ExecutionContext context;
  private final ContentCache<Verification> cache = new ContentCache<>();

  /** Creates a service that leases engines from {@code engines} and runs on {@code context}. */
  public VerificationService(ResourcePool<DataPlaneEngine> engines, ExecutionContext context) {
    this.engines = Objects.requireNonNull(engines, "engines");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Verifies one snapshot. */
  public Verification verify(Snapshot snapshot, SpecificationSet specifications) {
    throw new NotImplemented("VerificationService.verify");
  }

  /** Verifies several snapshots in parallel and returns the results in input order. */
  public List<Verification> verifyAll(List<Snapshot> snapshots, SpecificationSet specifications) {
    throw new NotImplemented("VerificationService.verifyAll");
  }
}
