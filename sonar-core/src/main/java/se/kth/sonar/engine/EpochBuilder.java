package se.kth.sonar.engine;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.prior.PriorFactor;
import se.kth.sonar.prior.RegionPrior;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.verify.Verification;

/**
 * Builds the epochs of a search and moves them along: the first epoch from the faulty network and
 * its verification, the next one after an adoption, and within an epoch the expansion, trimming and
 * removal of regions. The prior over an epoch's regions is computed on request.
 */
public final class EpochBuilder {
  private final ProgramService programs;
  private final SearchSpaceBuilder spaces;
  private final RegionPrior prior;
  private final boolean fixed;

  /** Creates a builder from the program service, the space builder and the prior. */
  public EpochBuilder(ProgramService programs, SearchSpaceBuilder spaces, RegionPrior prior) {
    this(programs, spaces, prior, false);
  }

  private EpochBuilder(
      ProgramService programs, SearchSpaceBuilder spaces, RegionPrior prior, boolean fixed) {
    this.programs = Objects.requireNonNull(programs, "programs");
    this.spaces = Objects.requireNonNull(spaces, "spaces");
    this.prior = Objects.requireNonNull(prior, "prior");
    this.fixed = fixed;
  }

  /**
   * Returns a builder whose prior is uniform over regions and stays so: {@link #with} adds no
   * factor (the uniform-prior ablation).
   */
  public static EpochBuilder uniform(ProgramService programs, SearchSpaceBuilder spaces) {
    return new EpochBuilder(programs, spaces, new RegionPrior(List.of()), true);
  }

  /** Returns a builder whose prior has {@code factor} as well, unless the prior is fixed. */
  public EpochBuilder with(PriorFactor factor) {
    return fixed ? this : new EpochBuilder(programs, spaces, prior.with(factor), false);
  }

  /** Returns the program service. */
  public ProgramService programs() {
    return programs;
  }

  /** Returns the search space builder. */
  public SearchSpaceBuilder spaces() {
    return spaces;
  }

  /** Returns the prior over regions. */
  public RegionPrior prior() {
    return prior;
  }

  /** Returns the first epoch: the faulty network's program and the space built over it. */
  public Epoch initial(RepairProblem problem, Verification baseline) {
    ProgramModel program = programs.model(problem.initial(), problem.topology());
    RepairState state = RepairState.initial(problem, baseline, program);
    return new Epoch(state, spaces.build(state));
  }

  /**
   * Returns the epoch after adopting repairs that turned the network into {@code snapshot}: its
   * program derived from the previous one, and the space refreshed for the new network. A repaired
   * network leaves nothing to search, so its space is empty.
   */
  public Epoch advance(Epoch epoch, Snapshot snapshot, Verification verification) {
    RepairState state = epoch.state();
    ProgramModel program =
        programs.model(snapshot, state.problem().topology(), Optional.of(state.program()));
    RepairState advanced = state.advance(snapshot, verification, program);
    SearchSpace space =
        advanced.solved()
            ? SearchSpace.of(List.of(), List.of(), epoch.space().capacity())
            : spaces.refresh(epoch.space(), advanced);
    return new Epoch(advanced, space);
  }

  /**
   * Returns the epoch with the candidates of region {@code id} generated; a region left without
   * candidates is removed.
   */
  public Epoch expand(Epoch epoch, Region.Id id) {
    Region region = epoch.space().region(id).orElseThrow();
    Region expanded = spaces.expand(region, epoch.state());
    return expanded.bound() == 0 ? without(epoch, List.of(id)) : replace(epoch, expanded);
  }

  /** Returns the epoch with {@code region} in place of the region with the same identifier. */
  public Epoch replace(Epoch epoch, Region region) {
    return new Epoch(epoch.state(), epoch.space().replace(region));
  }

  /** Returns the epoch without the regions {@code ids}. */
  public Epoch without(Epoch epoch, Collection<Region.Id> ids) {
    return new Epoch(epoch.state(), epoch.space().without(ids));
  }

  /** Returns the prior over the regions of {@code epoch}. */
  public Distribution<Region.Id> prior(Epoch epoch) {
    return prior.over(epoch.space(), epoch.state());
  }
}
