package se.kth.sonar.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.ContentHash;

class ContentCacheTest {
  private static final ContentHash KEY = ContentHash.of("snapshot");

  @Test
  void concurrentRequestsForOneKeyComputeOnce() throws InterruptedException, ExecutionException {
    ContentCache<Integer> cache = new ContentCache<>();
    AtomicInteger computations = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int task = 0; task < 16; task++) {
        results.add(
            executor.submit(
                () ->
                    cache.get(
                        KEY,
                        () -> {
                          computations.incrementAndGet();
                          awaitQuietly(release);
                          return 42;
                        })));
      }
      release.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get()).isEqualTo(42);
      }
    }
    assertThat(computations).hasValue(1);
    assertThat(cache.peek(KEY)).contains(42);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  void failuresAreNotRemembered() {
    ContentCache<Integer> cache = new ContentCache<>();
    assertThatThrownBy(
            () ->
                cache.get(
                    KEY,
                    () -> {
                      throw new IllegalStateException("verifier crashed");
                    }))
        .hasMessage("verifier crashed");
    assertThat(cache.peek(KEY)).isEmpty();
    assertThat(cache.get(KEY, () -> 7)).isEqualTo(7);
    assertThat(cache.get(KEY, () -> 8)).isEqualTo(7);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
