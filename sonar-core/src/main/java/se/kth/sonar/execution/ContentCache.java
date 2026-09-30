package se.kth.sonar.execution;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import se.kth.sonar.common.ContentHash;

/**
 * A concurrent memo table keyed by content digests.
 *
 * <p>Concurrent requests for the same key run the computation once and share its result. A failed
 * computation is not remembered, so a later request retries it. A bounded cache forgets every entry
 * once it holds more than its capacity, which keeps long runs from growing without limit at the
 * price of an occasional recomputation.
 *
 * @param <V> the value type
 */
public final class ContentCache<V> {
  private final ConcurrentHashMap<ContentHash, CompletableFuture<V>> entries =
      new ConcurrentHashMap<>();
  private final int capacity;

  /** Creates a cache without a capacity. */
  public ContentCache() {
    this(Integer.MAX_VALUE);
  }

  /** Creates a cache that forgets everything once it holds more than {@code capacity} entries. */
  public ContentCache(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.capacity = capacity;
  }

  /** Returns the value for {@code key}, running {@code computation} if no value is cached. */
  public V get(ContentHash key, Supplier<? extends V> computation) {
    Objects.requireNonNull(key, "key");
    if (entries.size() > capacity) {
      entries.clear();
    }
    CompletableFuture<V> mine = new CompletableFuture<>();
    CompletableFuture<V> existing = entries.putIfAbsent(key, mine);
    if (existing != null) {
      return await(existing);
    }
    try {
      V value = computation.get();
      mine.complete(value);
      return value;
    } catch (RuntimeException | Error failure) {
      entries.remove(key, mine);
      mine.completeExceptionally(failure);
      throw failure;
    }
  }

  /** Returns the value for {@code key} if it has been computed successfully. */
  public Optional<V> peek(ContentHash key) {
    CompletableFuture<V> future = entries.get(key);
    if (future == null || future.state() != Future.State.SUCCESS) {
      return Optional.empty();
    }
    return Optional.ofNullable(future.resultNow());
  }

  /** Returns the number of keys that are cached or being computed. */
  public int size() {
    return entries.size();
  }

  private static <V> V await(CompletableFuture<V> future) {
    try {
      return future.join();
    } catch (CompletionException wrapped) {
      Throwable cause = wrapped.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new UndeclaredThrowableException(cause);
    }
  }
}
