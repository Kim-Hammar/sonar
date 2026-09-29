package se.kth.sonar.search;

import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.state.RepairState;

/**
 * Builds and maintains the search space of a repair run.
 *
 * <p>{@link #build} creates one relation region per violated relation instance and one site region
 * per program object that the verifier's evidence implicates, with deferred candidates, and
 * partitions them with {@link SearchSpace#partitioned}. Regions are built per router in parallel on
 * the compute lane. {@link #expand} generates and admits the candidates of one region only when the
 * search needs them. {@link #refresh} rebuilds only the regions that read a configuration an
 * adoption changed.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class SearchSpaceBuilder {
  private final SeedGenerator seeds;
  private final ClosureEngine closure;
  private final Admissibility admissibility;
  private final SearchSpace.Capacity capacity;
  private final ExecutionContext context;

  /** Creates a builder from the generation stages. */
  public SearchSpaceBuilder(
      SeedGenerator seeds,
      ClosureEngine closure,
      Admissibility admissibility,
      SearchSpace.Capacity capacity,
      ExecutionContext context) {
    this.seeds = Objects.requireNonNull(seeds, "seeds");
    this.closure = Objects.requireNonNull(closure, "closure");
    this.admissibility = Objects.requireNonNull(admissibility, "admissibility");
    this.capacity = Objects.requireNonNull(capacity, "capacity");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Returns the search space of {@code state} with every region deferred. */
  public SearchSpace build(RepairState state) {
    throw new NotImplemented("SearchSpaceBuilder.build");
  }

  /** Returns {@code region} with its admitted candidates. */
  public Region expand(Region region, RepairState state) {
    throw new NotImplemented("SearchSpaceBuilder.expand");
  }

  /** Returns {@code space} with every region that is stale in {@code state} rebuilt. */
  public SearchSpace refresh(SearchSpace space, RepairState state) {
    throw new NotImplemented("SearchSpaceBuilder.refresh");
  }
}
