package se.kth.sonar.trace;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.verify.Verification;

/** One step of a scenario's repair, as recorded in the trace of a run. */
public sealed interface TraceEvent {
  /**
   * The scenario was loaded.
   *
   * @param problem the scenario's configurations, topology and specifications
   */
  record Loaded(RepairProblem problem) implements TraceEvent {
    /** Checks the problem. */
    public Loaded {
      Objects.requireNonNull(problem, "problem");
    }
  }

  /**
   * The faulty network was verified.
   *
   * @param verification the verification of the faulty configurations
   */
  record Baseline(Verification verification) implements TraceEvent {
    /** Checks the verification. */
    public Baseline {
      Objects.requireNonNull(verification, "verification");
    }
  }

  /**
   * A search space was built.
   *
   * @param epoch the state it was built for
   * @param space the search space
   * @param prior the prior over the regions, when the engine reports it
   */
  record SpaceBuilt(int epoch, SearchSpace space, Optional<Distribution<Region.Id>> prior)
      implements TraceEvent {
    /** Checks the components. */
    public SpaceBuilt {
      Objects.requireNonNull(space, "space");
      Objects.requireNonNull(prior, "prior");
    }

    /** A space whose prior is not reported. */
    public SpaceBuilt(int epoch, SearchSpace space) {
      this(epoch, space, Optional.empty());
    }
  }

  /**
   * The decision model was asked a question.
   *
   * @param problem the question with its context
   * @param outcome the answer, or why there is none, with the exchanges it took
   */
  record Decided(DecisionProblem problem, DecisionOutcome<?> outcome) implements TraceEvent {
    /** Checks the components. */
    public Decided {
      Objects.requireNonNull(problem, "problem");
      Objects.requireNonNull(outcome, "outcome");
    }
  }

  /**
   * A candidate was verified.
   *
   * @param region the region the candidate belongs to
   * @param hypothesis the candidate
   * @param verification the verification of the candidate's result
   * @param change how its verdict differs from the current state's
   */
  record Tried(
      Region.Id region, Hypothesis hypothesis, Verification verification, Verdict.Change change)
      implements TraceEvent {
    /** Checks the components. */
    public Tried {
      Objects.requireNonNull(region, "region");
      Objects.requireNonNull(hypothesis, "hypothesis");
      Objects.requireNonNull(verification, "verification");
      Objects.requireNonNull(change, "change");
    }
  }

  /**
   * Batfish could not analyse a candidate even after retrying it on a fresh engine.
   *
   * @param region the region the candidate belongs to
   * @param hypothesis the rejected candidate
   * @param reason the verifier failure
   */
  record Rejected(Region.Id region, Hypothesis hypothesis, String reason) implements TraceEvent {
    /** Checks the components. */
    public Rejected {
      Objects.requireNonNull(region, "region");
      Objects.requireNonNull(hypothesis, "hypothesis");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * Candidates were adopted.
   *
   * @param epoch the new state
   * @param hypotheses the adopted candidates
   * @param verification the verification of the new state
   */
  record Adopted(int epoch, List<Hypothesis> hypotheses, Verification verification)
      implements TraceEvent {
    /** Copies the candidates and checks the verification. */
    public Adopted {
      hypotheses = List.copyOf(hypotheses);
      Objects.requireNonNull(verification, "verification");
    }
  }

  /**
   * The scenario's repair ended.
   *
   * @param result the outcome
   */
  record Finished(RepairResult result) implements TraceEvent {
    /** Checks the result. */
    public Finished {
      Objects.requireNonNull(result, "result");
    }
  }

  /**
   * The scenario could not be repaired because something failed.
   *
   * @param reason what went wrong
   */
  record Failed(String reason) implements TraceEvent {
    /** Checks the reason. */
    public Failed {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
