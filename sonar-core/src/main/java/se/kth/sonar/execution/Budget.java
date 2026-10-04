package se.kth.sonar.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Limits on what a repair run may consume. Every method is safe to call from several threads.
 *
 * <p>The logical resources are charged by the orchestrating thread when it merges results, so a
 * parallel run consumes exactly what the sequential run would.
 */
public final class Budget {
  /** A countable resource. */
  public enum Resource {
    VERIFICATIONS,
    DECISIONS,
    PROVIDER_CALLS,
    INPUT_TOKENS,
    OUTPUT_TOKENS
  }

  private final Map<Resource, Long> limits;
  private final Map<Resource, AtomicLong> used;
  private final Optional<Duration> timeLimit;
  private final Clock clock;
  private final Instant started;

  private Budget(Map<Resource, Long> limits, Optional<Duration> timeLimit, Clock clock) {
    this.limits = Collections.unmodifiableMap(new EnumMap<>(limits));
    this.timeLimit = timeLimit;
    this.clock = clock;
    this.started = clock.instant();
    EnumMap<Resource, AtomicLong> counters = new EnumMap<>(Resource.class);
    for (Resource resource : Resource.values()) {
      counters.put(resource, new AtomicLong());
    }
    this.used = Collections.unmodifiableMap(counters);
  }

  /** Starts a budget without limits. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a budget without limits. */
  public static Budget unlimited() {
    return builder().build();
  }

  /**
   * Consumes {@code amount} of {@code resource} if that stays within the limit, and reports whether
   * it did. Nothing is consumed when the answer is no.
   */
  public boolean tryConsume(Resource resource, long amount) {
    requireAmount(amount);
    Long limit = limits.get(resource);
    AtomicLong counter = used.get(resource);
    while (true) {
      long current = counter.get();
      long next = Math.addExact(current, amount);
      if (limit != null && next > limit) {
        return false;
      }
      if (counter.compareAndSet(current, next)) {
        return true;
      }
    }
  }

  /** Records {@code amount} of {@code resource} that was consumed, even beyond the limit. */
  public void charge(Resource resource, long amount) {
    requireAmount(amount);
    used.get(resource).addAndGet(amount);
  }

  /** Returns how much of {@code resource} has been consumed. */
  public long used(Resource resource) {
    return used.get(resource).get();
  }

  /** Returns how much of {@code resource} remains, or nothing if it is unlimited. */
  public OptionalLong remaining(Resource resource) {
    Long limit = limits.get(resource);
    return limit == null
        ? OptionalLong.empty()
        : OptionalLong.of(Math.max(0, limit - used(resource)));
  }

  /** Returns whether {@code resource} has reached its limit. */
  public boolean exhausted(Resource resource) {
    Long limit = limits.get(resource);
    return limit != null && used(resource) >= limit;
  }

  /** Returns the time since the budget was created. */
  public Duration elapsed() {
    return Duration.between(started, clock.instant());
  }

  /** Returns whether the time limit has passed. */
  public boolean expired() {
    return timeLimit.map(limit -> elapsed().compareTo(limit) >= 0).orElse(false);
  }

  /** Returns a snapshot of the consumption of every resource. */
  public Map<Resource, Long> usage() {
    EnumMap<Resource, Long> usage = new EnumMap<>(Resource.class);
    used.forEach((resource, counter) -> usage.put(resource, counter.get()));
    return Collections.unmodifiableMap(usage);
  }

  private static void requireAmount(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("amount must not be negative: " + amount);
    }
  }

  /** Builds a {@link Budget}. */
  public static final class Builder {
    private final EnumMap<Resource, Long> limits = new EnumMap<>(Resource.class);
    private Optional<Duration> timeLimit = Optional.empty();
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    /** Limits {@code resource} to {@code limit}. */
    public Builder limit(Resource resource, long limit) {
      requireAmount(limit);
      limits.put(Objects.requireNonNull(resource, "resource"), limit);
      return this;
    }

    /** Limits the wall-clock time of the run. */
    public Builder timeLimit(Duration limit) {
      if (limit.isNegative()) {
        throw new IllegalArgumentException("time limit must not be negative: " + limit);
      }
      timeLimit = Optional.of(limit);
      return this;
    }

    /** Measures time with {@code clock}. */
    public Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /** Returns the budget; its clock starts now. */
    public Budget build() {
      return new Budget(limits, timeLimit, clock);
    }
  }
}
