package se.kth.sonar.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResourcePoolTest {
  @Test
  void createsLazilyAndReusesTheMostRecentlyReturnedResource() throws InterruptedException {
    AtomicInteger created = new AtomicInteger();
    try (ResourcePool<Integer> pool =
        ResourcePool.of(2, created::incrementAndGet, resource -> {})) {
      assertThat(pool.live()).isZero();
      int first;
      try (ResourcePool<Integer>.Lease lease = pool.lease()) {
        first = lease.resource();
      }
      try (ResourcePool<Integer>.Lease a = pool.lease();
          ResourcePool<Integer>.Lease b = pool.lease()) {
        assertThat(a.resource()).isEqualTo(first);
        assertThat(b.resource()).isNotEqualTo(first);
      }
      assertThat(created).hasValue(2);
      assertThat(pool.idle()).isEqualTo(2);
    }
  }

  @Test
  void waitsAtCapacity() throws InterruptedException {
    try (ResourcePool<Object> pool = ResourcePool.of(1, Object::new, resource -> {})) {
      try (ResourcePool<Object>.Lease _ = pool.lease()) {
        assertThat(pool.tryLease(Duration.ofMillis(20))).isEmpty();
      }
      Optional<ResourcePool<Object>.Lease> again = pool.tryLease(Duration.ofMillis(20));
      assertThat(again).isPresent();
      again.get().close();
    }
  }

  @Test
  void invalidatedResourcesAreDisposed() throws InterruptedException {
    List<Object> disposed = new CopyOnWriteArrayList<>();
    try (ResourcePool<Object> pool = ResourcePool.of(1, Object::new, disposed::add)) {
      Object broken;
      try (ResourcePool<Object>.Lease lease = pool.lease()) {
        broken = lease.resource();
        lease.invalidate();
      }
      assertThat(disposed).containsExactly(broken);
      assertThat(pool.live()).isZero();
      try (ResourcePool<Object>.Lease lease = pool.lease()) {
        assertThat(lease.resource()).isNotSameAs(broken);
      }
    }
  }

  @Test
  void closingDisposesIdleResourcesAndRejectsNewLeases() throws InterruptedException {
    List<Object> disposed = new CopyOnWriteArrayList<>();
    ResourcePool<Object> pool = ResourcePool.of(2, Object::new, disposed::add);
    ResourcePool<Object>.Lease held = pool.lease();
    try (ResourcePool<Object>.Lease idle = pool.lease()) {
      assertThat(idle.resource()).isNotNull();
    }
    pool.close();
    assertThat(disposed).hasSize(1);
    held.close();
    held.close();
    assertThat(disposed).hasSize(2);
    assertThatThrownBy(pool::lease).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aFailingFactoryReleasesItsPermit() throws InterruptedException {
    AtomicInteger calls = new AtomicInteger();
    try (ResourcePool<Integer> pool =
        ResourcePool.of(
            1,
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("engine failed to start");
              }
              return calls.get();
            },
            resource -> {})) {
      assertThatThrownBy(pool::lease).hasMessage("engine failed to start");
      try (ResourcePool<Integer>.Lease lease = pool.lease()) {
        assertThat(lease.resource()).isEqualTo(2);
      }
    }
  }

  @Test
  void neverLeasesMoreThanTheCapacity() {
    AtomicInteger inUse = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    try (ResourcePool<Object> pool = ResourcePool.of(3, Object::new, resource -> {})) {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int task = 0; task < 200; task++) {
          executor.execute(
              () -> {
                try (ResourcePool<Object>.Lease _ = pool.lease()) {
                  peak.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                  Thread.sleep(1);
                  inUse.decrementAndGet();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
        }
      }
      assertThat(pool.live()).isLessThanOrEqualTo(3);
    }
    assertThat(peak.get()).isBetween(1, 3);
  }
}
