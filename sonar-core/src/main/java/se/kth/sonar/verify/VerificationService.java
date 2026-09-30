package se.kth.sonar.verify;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.common.ContentHash;
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
 * leases one engine per snapshot, runs up to the pool's capacity at a time on virtual threads, and
 * returns the results in input order. An engine that fails is invalidated rather than returned to
 * the pool.
 */
public final class VerificationService {
  private static final int CACHE_CAPACITY = 256;

  private final ResourcePool<DataPlaneEngine> engines;
  private final ExecutionContext context;
  private final ContentCache<Verification> cache = new ContentCache<>(CACHE_CAPACITY);

  /** Creates a service that leases engines from {@code engines} and runs on {@code context}. */
  public VerificationService(ResourcePool<DataPlaneEngine> engines, ExecutionContext context) {
    this.engines = Objects.requireNonNull(engines, "engines");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Verifies one snapshot. */
  public Verification verify(Snapshot snapshot, SpecificationSet specifications) {
    return cache.get(key(snapshot, specifications), () -> analyse(snapshot, specifications));
  }

  /** Returns the verification of {@code snapshot} if it was computed before. */
  public Optional<Verification> cached(Snapshot snapshot, SpecificationSet specifications) {
    return cache.peek(key(snapshot, specifications));
  }

  /** Verifies several snapshots in parallel and returns the results in input order. */
  public List<Verification> verifyAll(List<Snapshot> snapshots, SpecificationSet specifications) {
    return context.fanOut(
        snapshots, engines.capacity(), snapshot -> verify(snapshot, specifications));
  }

  private static ContentHash key(Snapshot snapshot, SpecificationSet specifications) {
    return ContentHash.builder().add(snapshot.id()).add(specifications.id()).build();
  }

  private Verification analyse(Snapshot snapshot, SpecificationSet specifications) {
    ResourcePool<DataPlaneEngine>.Lease lease;
    try {
      lease = engines.lease();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for a data-plane engine", e);
    }
    try (lease) {
      DataPlaneEngine.Observation observation;
      try {
        observation = lease.resource().analyze(snapshot, specifications);
      } catch (RuntimeException | Error failure) {
        lease.invalidate();
        throw failure;
      }
      return new Verification(
          snapshot.id(), specifications.judge(observation.holding()), observation.evidence());
    }
  }
}
