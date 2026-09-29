package se.kth.sonar.engine;

import java.util.Objects;
import se.kth.sonar.belief.BeliefModel;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.decision.DecisionProblemFactory;
import se.kth.sonar.decision.DecisionService;
import se.kth.sonar.policy.SearchPolicy;
import se.kth.sonar.prior.RegionPrior;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.verify.VerificationService;

/**
 * Repairs one network.
 *
 * <p>The run verifies the faulty network, builds its program and search space, and then repeats:
 * the policy picks an action; assessments and rankings update the belief; candidates are verified
 * in batches whose results merge in candidate order; and improvements are adopted, after which the
 * stale regions are rebuilt. Every step is reported with {@link se.kth.sonar.trace.TraceSink#emit}
 * from the calling thread. The run stops when the network is repaired, the budget runs out, the run
 * scope's cancellation token is set, or the search space is empty.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class RepairEngine implements Repairer {
  private final Components components;

  /**
   * The services a run uses.
   *
   * @param programs builds program models
   * @param verifier verifies candidates
   * @param spaces builds and refreshes search spaces
   * @param prior the prior over regions
   * @param problems builds decision questions
   * @param decisions asks the decision model
   * @param beliefs updates beliefs
   * @param policy chooses actions
   * @param adoption adopts improvements
   */
  public record Components(
      ProgramService programs,
      VerificationService verifier,
      SearchSpaceBuilder spaces,
      RegionPrior prior,
      DecisionProblemFactory problems,
      DecisionService decisions,
      BeliefModel beliefs,
      SearchPolicy policy,
      Adoption adoption) {

    /** Checks that every service is present. */
    public Components {
      Objects.requireNonNull(programs, "programs");
      Objects.requireNonNull(verifier, "verifier");
      Objects.requireNonNull(spaces, "spaces");
      Objects.requireNonNull(prior, "prior");
      Objects.requireNonNull(problems, "problems");
      Objects.requireNonNull(decisions, "decisions");
      Objects.requireNonNull(beliefs, "beliefs");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(adoption, "adoption");
    }
  }

  /** Creates an engine from its services. */
  public RepairEngine(Components components) {
    this.components = Objects.requireNonNull(components, "components");
  }

  @Override
  public RepairResult repair(RepairProblem problem) {
    throw new NotImplemented("RepairEngine.repair");
  }
}
