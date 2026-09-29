package se.kth.sonar.execution;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A bounded pool of expensive resources that one thread uses at a time, such as data-plane engines.
 *
 * <p>Resources are created on first demand, up to the capacity, and the most recently returned
 * resource is leased first, which keeps warm resources busy. Waiting threads are served in arrival
 * order.
 *
 * @param <T> the resource type
 */
public final class ResourcePool<T> implements AutoCloseable {
  private final int capacity;
  private final Supplier<? extends T> factory;
  private final Consumer<? super T> disposer;
  private final Semaphore permits;
  private final ReentrantLock lock = new ReentrantLock();
  private final Deque<T> idle = new ArrayDeque<>();
  private int live;
  private boolean closed;

  private ResourcePool(int capacity, Supplier<? extends T> factory, Consumer<? super T> disposer) {
    this.capacity = capacity;
    this.factory = factory;
    this.disposer = disposer;
    this.permits = new Semaphore(capacity, true);
  }

  /** Creates a pool of at most {@code capacity} resources made by {@code factory}. */
  public static <T> ResourcePool<T> of(
      int capacity, Supplier<? extends T> factory, Consumer<? super T> disposer) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    return new ResourcePool<>(
        capacity,
        Objects.requireNonNull(factory, "factory"),
        Objects.requireNonNull(disposer, "disposer"));
  }

  /** Waits until a resource is free and leases it to the calling thread. */
  public Lease lease() throws InterruptedException {
    permits.acquire();
    return acquired();
  }

  /** Leases a resource if one becomes free within {@code timeout}. */
  public Optional<Lease> tryLease(Duration timeout) throws InterruptedException {
    if (!permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
      return Optional.empty();
    }
    return Optional.of(acquired());
  }

  /** Returns the maximum number of resources. */
  public int capacity() {
    return capacity;
  }

  /** Returns the number of resources created and not yet disposed. */
  public int live() {
    lock.lock();
    try {
      return live;
    } finally {
      lock.unlock();
    }
  }

  /** Returns the number of resources waiting to be leased. */
  public int idle() {
    lock.lock();
    try {
      return idle.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Disposes the idle resources and rejects new leases. Resources that are leased at the time are
   * disposed when their leases close.
   */
  @Override
  public void close() {
    List<T> drained;
    lock.lock();
    try {
      if (closed) {
        return;
      }
      closed = true;
      drained = new ArrayList<>(idle);
      idle.clear();
      live -= drained.size();
    } finally {
      lock.unlock();
    }
    disposeAll(drained);
  }

  private Lease acquired() {
    Optional<T> reused;
    lock.lock();
    try {
      if (closed) {
        permits.release();
        throw new IllegalStateException("the resource pool is closed");
      }
      reused = Optional.ofNullable(idle.pollFirst());
      if (reused.isEmpty()) {
        live++;
      }
    } finally {
      lock.unlock();
    }
    if (reused.isPresent()) {
      return new Lease(reused.get());
    }
    try {
      return new Lease(Objects.requireNonNull(factory.get(), "factory returned null"));
    } catch (RuntimeException | Error failure) {
      lock.lock();
      try {
        live--;
      } finally {
        lock.unlock();
      }
      permits.release();
      throw failure;
    }
  }

  private void disposeAll(List<T> resources) {
    RuntimeException first = null;
    for (T resource : resources) {
      try {
        disposer.accept(resource);
      } catch (RuntimeException failure) {
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }

  /** Exclusive use of one pooled resource; closing the lease returns the resource. */
  public final class Lease implements AutoCloseable {
    private final T resource;
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile boolean valid = true;

    private Lease(T resource) {
      this.resource = resource;
    }

    /** Returns the leased resource. */
    public T resource() {
      return resource;
    }

    /** Marks the resource as broken, so that closing the lease disposes it. */
    public void invalidate() {
      valid = false;
    }

    /** Returns the resource to the pool, or disposes it if it is broken or the pool is closed. */
    @Override
    public void close() {
      if (!released.compareAndSet(false, true)) {
        return;
      }
      boolean dispose;
      lock.lock();
      try {
        dispose = closed || !valid;
        if (dispose) {
          live--;
        } else {
          idle.addFirst(resource);
        }
      } finally {
        lock.unlock();
      }
      try {
        if (dispose) {
          disposer.accept(resource);
        }
      } finally {
        permits.release();
      }
    }
  }
}
