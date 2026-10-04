package se.kth.sonar.decision;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.execution.RunScope;

/**
 * Asks the decision model questions concurrently and within budget.
 *
 * <p>Models are leased from a pool. Answers are cached by problem key, so identical questions share
 * one request. A decision is charged to the run's budget when the search asks for it, and the
 * provider calls and tokens the answer took are charged when the provider is actually called, so
 * cached answers cost no tokens and parallel runs charge exactly what sequential runs do.
 */
public final class DecisionService {
  private static final int CACHE_CAPACITY = 4096;

  private final ResourcePool<DecisionModel> models;
  private final ContentCache<DecisionOutcome<?>> answers = new ContentCache<>(CACHE_CAPACITY);

  /** Creates a service that leases models from {@code models}. */
  public DecisionService(ResourcePool<DecisionModel> models) {
    this.models = Objects.requireNonNull(models, "models");
  }

  /** Asks which region of a partition holds the repair. */
  public DecisionOutcome<RegionAssessment> assess(DecisionProblem.RegionChoice problem) {
    return ask(problem, Budget.Resource.DECISIONS, model -> model.assess(problem));
  }

  /** Asks how to order the candidates of a region. */
  public DecisionOutcome<HypothesisRanking> rank(DecisionProblem.HypothesisChoice problem) {
    return ask(problem, Budget.Resource.DECISIONS, model -> model.rank(problem));
  }

  private <T> DecisionOutcome<T> ask(
      DecisionProblem problem,
      Budget.Resource counter,
      Function<DecisionModel, DecisionOutcome<T>> question) {
    Optional<Budget> budget = RunScope.current().map(RunScope::budget);
    if (budget.isPresent() && !budget.get().tryConsume(counter, 1)) {
      return new DecisionOutcome.Stopped<>(counter);
    }
    @SuppressWarnings("unchecked")
    DecisionOutcome<T> outcome =
        (DecisionOutcome<T>) answers.get(problem.key(), () -> compute(question, budget));
    return outcome;
  }

  private <T> DecisionOutcome<T> compute(
      Function<DecisionModel, DecisionOutcome<T>> question, Optional<Budget> budget) {
    ResourcePool<DecisionModel>.Lease lease;
    try {
      lease = models.lease();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for a decision model", e);
    }
    DecisionOutcome<T> outcome;
    try (lease) {
      try {
        outcome = question.apply(lease.resource());
      } catch (RuntimeException | Error failure) {
        lease.invalidate();
        throw failure;
      }
    }
    if (budget.isPresent()) {
      DecisionOutcome.Usage usage = outcome.usage();
      budget.get().charge(Budget.Resource.PROVIDER_CALLS, usage.providerCalls());
      budget.get().charge(Budget.Resource.INPUT_TOKENS, usage.inputTokens());
      budget.get().charge(Budget.Resource.OUTPUT_TOKENS, usage.outputTokens());
    }
    return outcome;
  }
}
