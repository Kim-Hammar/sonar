package se.kth.sonar.app;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import se.kth.sonar.batfish.BatfishEngine;
import se.kth.sonar.belief.BeliefModel;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.decision.DecisionModel;
import se.kth.sonar.decision.DecisionProblemFactory;
import se.kth.sonar.decision.DecisionService;
import se.kth.sonar.engine.Adoption;
import se.kth.sonar.engine.BenchmarkRunner;
import se.kth.sonar.engine.EpochBuilder;
import se.kth.sonar.engine.RepairEngine;
import se.kth.sonar.engine.Repairer;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.generate.ValueDomains;
import se.kth.sonar.jev.CachingTransport;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.jev.JevDecisionModel;
import se.kth.sonar.jev.JevRequestCodec;
import se.kth.sonar.policy.GreedyPolicy;
import se.kth.sonar.prior.CounterexampleFactor;
import se.kth.sonar.prior.DropFactor;
import se.kth.sonar.prior.FrontierFactor;
import se.kth.sonar.prior.PolicyCutFactor;
import se.kth.sonar.prior.RegionPrior;
import se.kth.sonar.prior.RelationFactor;
import se.kth.sonar.prior.SpectrumFactor;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.trace.TraceSink;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.VerificationService;

/**
 * The assembled repair pipeline of one run: the execution context, the pools of data-plane engines
 * and decision models, the repair engine, and the benchmark runner. The command line and the web
 * server both run agents through it.
 */
public final class Pipeline implements AutoCloseable {
  private static final int PARTITION_REGIONS = 16;
  private static final int PARTITION_HYPOTHESES = 64;
  private static final int PAYLOAD_LIMIT = 60_000;

  private final RunSettings settings;
  private final ScenarioRepository scenarios;
  private final ExecutionContext context;
  private final ResourcePool<DataPlaneEngine> engines;
  private final ResourcePool<DecisionModel> models;
  private final Repairer repairer;
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Which agent repairs the scenarios. */
  public sealed interface Agent {
    /**
     * The repair engine.
     *
     * @param decisions where and how the engine asks the decision model
     */
    record Engine(HttpTransport.Settings decisions) implements Agent {
      /** Checks the settings. */
      public Engine {
        Objects.requireNonNull(decisions, "decisions");
      }
    }

    /**
     * The deterministic scripted repairer, for demonstrations and tests.
     *
     * @param pace the pause between its steps
     */
    record Scripted(Duration pace) implements Agent {
      /** Checks the pace. */
      public Scripted {
        Objects.requireNonNull(pace, "pace");
      }
    }
  }

  private Pipeline(Workspace workspace, RunSettings settings, Agent agent) {
    this.settings = settings;
    this.scenarios = new ScenarioRepository(workspace.dataset());
    this.context = ExecutionContext.open(settings.parallelism(), List.of(RunScope.CURRENT));
    AtomicInteger engineCount = new AtomicInteger();
    this.engines =
        ResourcePool.of(
            settings.parallelism().verificationWorkers(),
            () ->
                new BatfishEngine(
                    new BatfishEngine.Settings(
                        workspace.engineStorage(engineCount.incrementAndGet()))),
            DataPlaneEngine::close);
    HttpTransport.Settings decisions =
        agent instanceof Agent.Engine engine
            ? engine.decisions()
            : HttpTransport.Settings.openRouter();
    this.models =
        ResourcePool.of(
            settings.parallelism().decisionConcurrency(),
            () ->
                new JevDecisionModel(
                    new CachingTransport(new HttpTransport(decisions), workspace.decisionCache()),
                    new JevRequestCodec(decisions.model()),
                    PAYLOAD_LIMIT),
            model -> {});
    this.repairer =
        switch (agent) {
          case Agent.Engine _ -> new RepairEngine(components(Dialect.load("cisco-ios")));
          case Agent.Scripted scripted -> new ScriptedRepairer(scripted.pace());
        };
  }

  /** Opens the pipeline of a run in {@code workspace} with {@code settings}. */
  public static Pipeline open(Workspace workspace, RunSettings settings, Agent agent) {
    return new Pipeline(
        Objects.requireNonNull(workspace, "workspace"),
        Objects.requireNonNull(settings, "settings"),
        Objects.requireNonNull(agent, "agent"));
  }

  /** Returns the scenarios of the workspace's dataset. */
  public ScenarioRepository scenarios() {
    return scenarios;
  }

  /**
   * Repairs {@code names} with the run's settings, sending every scenario's trace to {@code sink},
   * and returns the outcomes in scenario order.
   */
  public List<BenchmarkRunner.Outcome> run(
      List<String> names, Cancellation cancellation, TraceSink sink) {
    if (closed.get()) {
      throw new IllegalStateException("the pipeline is closed");
    }
    return new BenchmarkRunner(repairer, context)
        .run(
            names,
            name -> scenarios.load(name, settings.specificationPercent()),
            settings::newBudget,
            settings.seed(),
            cancellation,
            sink);
  }

  /** Stops unfinished work and releases the engines and models. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try (engines;
        models) {
      context.close();
    }
  }

  /**
   * Returns the epoch builder of {@code dialect} on {@code context}: the program service, the
   * search space builder with the generation bounds and partition capacity of a run, and the prior
   * with its factors in order. It is what a run searches with, and what inspects a scenario's
   * search space without a run.
   */
  public static EpochBuilder epochs(Dialect dialect, ExecutionContext context) {
    ProgramService programs = new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
    Bounds bounds = Bounds.defaults();
    SeedGenerator seeds =
        new SeedGenerator(dialect.schema(), new ValueDomains(dialect.schema(), bounds), bounds);
    SearchSpaceBuilder spaces =
        new SearchSpaceBuilder(
            seeds,
            new ClosureEngine(programs, seeds, bounds),
            new Admissibility(programs, context),
            new SearchSpace.Capacity(PARTITION_REGIONS, PARTITION_HYPOTHESES),
            bounds,
            context);
    RegionPrior prior =
        new RegionPrior(
            List.of(
                new SpectrumFactor(),
                new RelationFactor(programs.evaluator()),
                new CounterexampleFactor(),
                new PolicyCutFactor(),
                new FrontierFactor(),
                new DropFactor()));
    return new EpochBuilder(programs, spaces, prior);
  }

  private RepairEngine.Components components(Dialect dialect) {
    VerificationService verifier = new VerificationService(engines, context);
    return new RepairEngine.Components(
        epochs(dialect, context),
        verifier,
        new DecisionProblemFactory(PAYLOAD_LIMIT),
        new DecisionService(models, context),
        new BeliefModel(),
        new GreedyPolicy(),
        new Adoption(verifier));
  }
}
