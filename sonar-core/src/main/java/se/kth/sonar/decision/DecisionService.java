package se.kth.sonar.decision;

import java.util.Objects;
import java.util.concurrent.Future;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ResourcePool;

/**
 * Asks the decision model questions concurrently and within budget.
 *
 * <p>Models are leased from a pool. Answers are cached by scenario and problem key, so identical
 * questions of a scenario share one request, and a scenario's answers are released when the
 * scenario ends. Decisions, provider calls and tokens are charged on the orchestrating thread when
 * the search uses an answer, so parallel runs charge exactly what sequential runs do; questions
 * asked ahead of need with {@link #prefetch} are charged to the speculative counters until then.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class DecisionService {
  private final ResourcePool<DecisionModel> models;
  private final ExecutionContext context;
  private final ContentCache<DecisionOutcome<?>> answers = new ContentCache<>();

  /** Creates a service that leases models from {@code models} and runs on {@code context}. */
  public DecisionService(ResourcePool<DecisionModel> models, ExecutionContext context) {
    this.models = Objects.requireNonNull(models, "models");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Asks which region of a partition holds the repair. */
  public DecisionOutcome<RegionAssessment> assess(DecisionProblem.RegionChoice problem) {
    throw new NotImplemented("DecisionService.assess");
  }

  /** Asks how to order the candidates of a region. */
  public DecisionOutcome<HypothesisRanking> rank(DecisionProblem.HypothesisChoice problem) {
    throw new NotImplemented("DecisionService.rank");
  }

  /** Starts asking {@code problem} on a virtual thread while the caller continues. */
  public Future<DecisionOutcome<RegionAssessment>> prefetch(DecisionProblem.RegionChoice problem) {
    throw new NotImplemented("DecisionService.prefetch");
  }
}
