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

  /**
   * Verifies each candidate independently, retrying once with a fresh engine and reporting a
   * deterministic rejection instead of failing the whole batch when Batfish cannot analyse one
   * candidate. Baselines and adopted states still use {@link #verify} and therefore fail loudly.
   */
  public List<Attempt> attemptAll(List<Snapshot> snapshots, SpecificationSet specifications) {
    return context.fanOut(
        snapshots, engines.capacity(), snapshot -> attempt(snapshot, specifications));
  }

  /** The result of attempting to verify one candidate snapshot. */
  public sealed interface Attempt {
    /** A candidate Batfish analysed successfully. */
    record Success(Verification verification) implements Attempt {
      /** Checks the verification. */
      public Success {
        Objects.requireNonNull(verification, "verification");
      }
    }

    /** A candidate Batfish rejected on two independent engines. */
    record Rejected(String reason) implements Attempt {
      /** Checks the reason. */
      public Rejected {
        Objects.requireNonNull(reason, "reason");
      }
    }
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

  /** Retries once and preserves both distinct failures; {@code addSuppressed} rejects itself. */
  @SuppressWarnings("ReferenceEquality")
  private Attempt attempt(Snapshot snapshot, SpecificationSet specifications) {
    try {
      return new Attempt.Success(verify(snapshot, specifications));
    } catch (RuntimeException first) {
      if (Thread.currentThread().isInterrupted()) {
        throw first;
      }
      try {
        return new Attempt.Success(analyse(snapshot, specifications));
      } catch (RuntimeException second) {
        if (second != first) {
          second.addSuppressed(first);
        }
        String message = second.getMessage();
        return new Attempt.Rejected(
            second.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message));
      }
    }
  }
}
