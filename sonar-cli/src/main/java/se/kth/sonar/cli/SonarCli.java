package se.kth.sonar.cli;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import se.kth.sonar.app.Pipeline;
import se.kth.sonar.app.RunLog;
import se.kth.sonar.app.RunSettings;
import se.kth.sonar.app.RunStatus;
import se.kth.sonar.app.RunStore;
import se.kth.sonar.app.Versions;
import se.kth.sonar.app.Workspace;
import se.kth.sonar.app.theta.ThetaFigure;
import se.kth.sonar.app.theta.ThetaReport;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.engine.BenchmarkRunner;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.policy.KoopmanPolicy;
import se.kth.sonar.web.WebServer;

/** The {@code sonar} command line. */
@Command(
    name = "sonar",
    mixinStandardHelpOptions = true,
    versionProvider = SonarCli.Version.class,
    description = "SONAR: Search-Optimal Network Agentic Repair.",
    subcommands = {SonarCli.Repair.class, SonarCli.Serve.class, SonarCli.Theta.class})
public final class SonarCli implements Runnable {
  static final int NOT_IMPLEMENTED = 3;
  static final String DATASET = "dataset/main_dataset";
  static final String OUTPUT = "output";

  @Spec private CommandSpec spec;

  /** Runs the command line and exits with its status. */
  public static void main(String[] args) {
    PrintWriter out =
        new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
    PrintWriter err =
        new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
    System.exit(execute(out, err, args));
  }

  /** Runs the command line, writing to {@code out} and {@code err}, and returns the exit status. */
  public static int execute(PrintWriter out, PrintWriter err, String... args) {
    CommandLine commandLine = new CommandLine(new SonarCli());
    commandLine.setOut(out);
    commandLine.setErr(err);
    commandLine.setExecutionExceptionHandler(SonarCli::handle);
    return commandLine.execute(args);
  }

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  private static int handle(Exception error, CommandLine command, CommandLine.ParseResult parsed)
      throws Exception {
    if (error instanceof NotImplemented missing) {
      command.getErr().println("sonar: " + missing.getMessage());
      return NOT_IMPLEMENTED;
    }
    throw error;
  }

  /** Prints the versions of sonar, the embedded Batfish, and the Java runtime. */
  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {
        "sonar " + Versions.sonar() + " (SONAR: Search-Optimal Network Agentic Repair)",
        "Batfish " + Versions.batfish(),
        "Java " + Versions.java()
      };
    }
  }

  /**
   * Returns the workspace of a command.
   *
   * @throws CommandLine.ParameterException if the dataset directory does not exist
   */
  static Workspace workspace(CommandSpec spec, Path dataset, Path output) {
    Workspace workspace = new Workspace(dataset, output);
    if (!Files.isDirectory(workspace.dataset())) {
      throw new CommandLine.ParameterException(
          spec.commandLine(),
          "no dataset at "
              + workspace.dataset()
              + "; run scripts/fetch-dataset.sh or pass --dataset DIR");
    }
    return workspace;
  }

  /** Where the decision model is asked. */
  static final class DecisionOptions {
    @Option(
        names = "--model",
        paramLabel = "NAME",
        description = "Decision model (default: ${DEFAULT-VALUE}).")
    private String model = HttpTransport.Settings.openRouter().model();

    @Option(
        names = "--endpoint",
        paramLabel = "URL",
        description = "Decision endpoint (default: ${DEFAULT-VALUE}).")
    private URI endpoint = HttpTransport.Settings.openRouter().endpoint();

    @Option(
        names = "--api-key-variable",
        paramLabel = "NAME",
        description =
            "Environment variable containing the endpoint API key (default: ${DEFAULT-VALUE}).")
    private String apiKeyVariable = HttpTransport.Settings.openRouter().apiKeyVariable();

    @Option(
        names = "--decision-api",
        paramLabel = "NAME",
        description =
            "Protocol of the decision endpoint: decisions asks Jev's typed questions; chat asks an"
                + " LLM the same questions through OpenAI-compatible chat completions, an ablation"
                + " (default: ${DEFAULT-VALUE}).")
    private DecisionApi decisionApi = DecisionApi.decisions;

    @Option(
        names = "--decision-timeout",
        paramLabel = "SECONDS",
        description =
            "Seconds to wait for one decision reply (default: 45, or 180 with --decision-api"
                + " chat).")
    private Long decisionTimeout;

    /** The protocols a decision endpoint may speak. */
    enum DecisionApi {
      decisions,
      chat
    }

    HttpTransport.Settings settings() {
      HttpTransport.Settings defaults = HttpTransport.Settings.openRouter();
      boolean chat = decisionApi == DecisionApi.chat;
      Duration timeout =
          decisionTimeout != null
              ? Duration.ofSeconds(decisionTimeout)
              : chat ? Duration.ofSeconds(180) : defaults.timeout();
      return new HttpTransport.Settings(
          endpoint,
          model,
          apiKeyVariable,
          timeout,
          chat ? HttpTransport.Api.CHAT : HttpTransport.Api.DECISIONS);
    }
  }

  /** Estimates each decision model's θ from recorded runs and writes the comparison figure. */
  @Command(
      name = "theta",
      mixinStandardHelpOptions = true,
      description =
          "Estimates θ of the search function q(r, θ) = 1 - exp(-θ r) for decision models from"
              + " recorded runs.")
  static final class Theta implements Callable<Integer> {
    @Option(
        names = "--model-runs",
        required = true,
        paramLabel = "NAME=DIR",
        description =
            "A model's display name and its runs: a run directory or a directory of runs."
                + " Repeat for each model.")
    private Map<String, Path> runs = new LinkedHashMap<>();

    @Option(
        names = "--output",
        required = true,
        paramLabel = "DIR",
        description = "Where to write theta.json, observations.csv and theta.tex.")
    private Path output;

    @Option(
        names = "--seed",
        defaultValue = "0",
        paramLabel = "N",
        description = "Seed of the bootstrap (default: ${DEFAULT-VALUE}).")
    private long seed;

    @Spec private CommandSpec spec;

    @Override
    public Integer call() throws IOException {
      PrintWriter out = spec.commandLine().getOut();
      List<ThetaFigure.Series> series;
      try {
        series = ThetaReport.write(runs, output, seed);
      } catch (IllegalArgumentException e) {
        spec.commandLine().getErr().println(e.getMessage());
        return 1;
      }
      out.printf(
          Locale.ROOT,
          "%-24s %8s %8s %8s %9s %9s %10s%n",
          "model",
          "theta",
          "low",
          "high",
          "episodes",
          "censored",
          "incomplete");
      for (ThetaFigure.Series one : series) {
        out.printf(
            Locale.ROOT,
            "%-24s %8.3f %8.3f %8.3f %9d %9d %10d%n",
            one.model(),
            one.estimate().theta(),
            one.estimate().low(),
            one.estimate().high(),
            one.observations().episodes(),
            one.observations().censored(),
            one.observations().incomplete());
      }
      out.println("wrote theta.json, observations.csv and theta.tex to " + output);
      return 0;
    }
  }

  /** Repairs scenarios of a benchmark dataset and records the run in the output directory. */
  @Command(
      name = "repair",
      mixinStandardHelpOptions = true,
      description = "Repairs scenarios of a benchmark dataset.")
  static final class Repair implements Callable<Integer> {
    @Option(
        names = "--dataset",
        defaultValue = DATASET,
        paramLabel = "DIR",
        description =
            "Dataset directory with one directory per scenario (default: ${DEFAULT-VALUE}).")
    private Path dataset;

    @Option(
        names = "--scenario",
        required = true,
        split = ",",
        paramLabel = "NAME",
        description = "Scenarios to repair, separated by commas.")
    private List<String> scenarios;

    @Option(
        names = "--output",
        defaultValue = OUTPUT,
        paramLabel = "DIR",
        description =
            "Directory for run logs, the decision cache and engine storage"
                + " (default: ${DEFAULT-VALUE}).")
    private Path output;

    @Option(names = "--name", paramLabel = "TEXT", description = "Name of the run.")
    private String name = "";

    @Option(
        names = "--specifications",
        defaultValue = "10",
        paramLabel = "PERCENT",
        description =
            "Share of the specifications to use, in per cent (default: ${DEFAULT-VALUE}).")
    private int percent;

    @Option(
        names = "--verification-batch",
        defaultValue = "5",
        paramLabel = "N",
        description =
            "Candidates the greedy policy verifies at a time, and the fewest the koopman policy"
                + " does (default: ${DEFAULT-VALUE}).")
    private int verificationBatch;

    @Option(
        names = "--policy",
        defaultValue = "greedy",
        paramLabel = "NAME",
        description =
            "Search policy: greedy verifies a fixed batch from the most probable region; koopman"
                + " sizes the batch by Koopman's index and may stop early (default:"
                + " ${DEFAULT-VALUE}).")
    private Policy policy;

    @Option(
        names = "--theta",
        defaultValue = "" + KoopmanPolicy.DEFAULT_THETA,
        paramLabel = "θ",
        description =
            "Koopman policy: how well the decision model ranks candidates, θ in q(r, θ) = 1 -"
                + " exp(-θ r) (default: ${DEFAULT-VALUE}).")
    private double theta;

    @Option(
        names = "--lambda",
        defaultValue = "" + KoopmanPolicy.DEFAULT_LAMBDA,
        paramLabel = "λ",
        description =
            "Koopman policy: the cost of one violated specification left at the end, in"
                + " verifications (default: ${DEFAULT-VALUE}).")
    private double lambda;

    @Option(
        names = "--prior",
        defaultValue = "informed",
        paramLabel = "NAME",
        description =
            "Prior over regions: informed weighs them by the evidence factors and experience;"
                + " uniform gives every region the same weight, an ablation (default:"
                + " ${DEFAULT-VALUE}).")
    private Prior prior;

    @Option(
        names = "--no-region-assessment",
        description =
            "Never ask the decision model to assess regions, so the belief learns from"
                + " verification alone and the model only ranks candidates (an ablation).")
    private boolean noRegionAssessment;

    @Option(
        names = "--no-localization",
        description =
            "Build search regions on every router instead of only on the routers the verifier's"
                + " evidence implicates in a violated specification (an ablation).")
    private boolean noLocalization;

    /** The search policies a run can choose. */
    enum Policy {
      greedy,
      koopman
    }

    /** The priors a run can choose. */
    enum Prior {
      informed,
      uniform
    }

    @Option(
        names = "--max-verifications",
        paramLabel = "N",
        description = "Maximum verifications for each scenario.")
    private Long maxVerifications;

    @Option(
        names = "--max-decisions",
        paramLabel = "N",
        description = "Maximum decisions for each scenario.")
    private Long maxDecisions;

    @Option(
        names = "--max-input-tokens",
        paramLabel = "N",
        description = "Maximum input tokens for each scenario.")
    private Long maxInputTokens;

    @Option(
        names = "--time-limit",
        paramLabel = "SECONDS",
        description = "Maximum seconds for each scenario.")
    private Long timeLimit;

    @Option(
        names = "--seed",
        defaultValue = "0",
        paramLabel = "N",
        description = "Seed of every random choice (default: ${DEFAULT-VALUE}).")
    private long seed;

    @Option(
        names = "--verification-workers",
        defaultValue = "1",
        paramLabel = "N",
        description = "Batfish engines verifying at the same time (default: ${DEFAULT-VALUE}).")
    private int verificationWorkers;

    @Option(
        names = "--decision-concurrency",
        defaultValue = "4",
        paramLabel = "N",
        description = "Decision requests in flight at the same time (default: ${DEFAULT-VALUE}).")
    private int decisionConcurrency;

    @Option(
        names = "--generation-parallelism",
        paramLabel = "N",
        description = "Threads generating candidates (default: the number of processors).")
    private int generationParallelism = Runtime.getRuntime().availableProcessors();

    @Option(
        names = "--scenario-parallelism",
        defaultValue = "1",
        paramLabel = "N",
        description = "Scenarios repaired at the same time (default: ${DEFAULT-VALUE}).")
    private int scenarioParallelism;

    @CommandLine.ArgGroup(exclusive = false)
    private DecisionOptions decisions = new DecisionOptions();

    @Spec private CommandSpec spec;

    @Override
    public Integer call() throws IOException {
      Map<Budget.Resource, Long> limits = new EnumMap<>(Budget.Resource.class);
      Optional.ofNullable(maxVerifications)
          .ifPresent(limit -> limits.put(Budget.Resource.VERIFICATIONS, limit));
      Optional.ofNullable(maxDecisions)
          .ifPresent(limit -> limits.put(Budget.Resource.DECISIONS, limit));
      Optional.ofNullable(maxInputTokens)
          .ifPresent(limit -> limits.put(Budget.Resource.INPUT_TOKENS, limit));
      RunSettings settings =
          new RunSettings(
              percent,
              verificationBatch,
              new ParallelismConfig(
                  verificationWorkers,
                  decisionConcurrency,
                  generationParallelism,
                  scenarioParallelism),
              limits,
              Optional.ofNullable(timeLimit).map(Duration::ofSeconds),
              seed,
              policy == Policy.koopman
                  ? Optional.of(new KoopmanPolicy.Parameters(theta, lambda))
                  : Optional.empty(),
              prior == Prior.uniform,
              !noRegionAssessment,
              !noLocalization);
      Workspace workspace = workspace(spec, dataset, output);
      HttpTransport.Settings model = decisions.settings();
      PrintWriter out = spec.commandLine().getOut();
      try (RunLog log =
              new RunStore(workspace.runs(), Clock.systemUTC())
                  .create(name, scenarios, settings, false, model.model());
          Pipeline pipeline =
              Pipeline.open(workspace, settings, new Pipeline.Agent.Engine(model))) {
        out.println("run " + log.document().id() + " in " + log.directory());
        log.update(
            document -> document.withStatus(RunStatus.RUNNING, System.currentTimeMillis(), null));
        List<BenchmarkRunner.Outcome> outcomes = pipeline.run(scenarios, new Cancellation(), log);
        log.update(
            document -> document.withStatus(RunStatus.FINISHED, System.currentTimeMillis(), null));
        List<String> failed = new ArrayList<>();
        for (BenchmarkRunner.Outcome outcome : outcomes) {
          String line =
              switch (outcome) {
                case BenchmarkRunner.Outcome.Completed completed ->
                    completed.scenario()
                        + "  "
                        + completed
                            .result()
                            .stop()
                            .getClass()
                            .getSimpleName()
                            .toLowerCase(Locale.ROOT);
                case BenchmarkRunner.Outcome.Failed failure ->
                    failure.scenario() + "  failed: " + failure.reason();
                case BenchmarkRunner.Outcome.Cancelled cancelled ->
                    cancelled.scenario() + "  cancelled";
              };
          out.println(line);
          if (!(outcome instanceof BenchmarkRunner.Outcome.Completed)) {
            failed.add(outcome.scenario());
          }
        }
        return failed.isEmpty() ? 0 : 1;
      }
    }
  }

  /** Serves the web interface. */
  @Command(
      name = "serve",
      mixinStandardHelpOptions = true,
      description = "Serves the web interface.")
  static final class Serve implements Callable<Integer> {
    @Option(
        names = "--dataset",
        defaultValue = DATASET,
        paramLabel = "DIR",
        description =
            "Dataset directory with one directory per scenario (default: ${DEFAULT-VALUE}).")
    private Path dataset;

    @Option(
        names = "--output",
        defaultValue = OUTPUT,
        paramLabel = "DIR",
        description =
            "Directory for run logs, the decision cache and engine storage"
                + " (default: ${DEFAULT-VALUE}).")
    private Path output;

    @Option(
        names = "--host",
        defaultValue = "127.0.0.1",
        paramLabel = "ADDRESS",
        description = "Address to listen on (default: ${DEFAULT-VALUE}).")
    private String host;

    @Option(
        names = "--port",
        defaultValue = "8080",
        paramLabel = "N",
        description = "Port to listen on (default: ${DEFAULT-VALUE}).")
    private int port;

    @Option(
        names = "--allow-host",
        paramLabel = "NAME",
        description = "Further host name the server answers; repeatable.")
    private List<String> allowedHosts = new ArrayList<>();

    @Option(
        names = "--demo",
        description = "Run the scripted repairer instead of the engine, to try the interface.")
    private boolean demo;

    @Option(
        names = "--demo-pace",
        defaultValue = "400",
        paramLabel = "MILLISECONDS",
        description = "Pause between the scripted repairer's steps (default: ${DEFAULT-VALUE}).")
    private long demoPace;

    @CommandLine.ArgGroup(exclusive = false)
    private DecisionOptions decisions = new DecisionOptions();

    @Spec private CommandSpec spec;

    @Override
    public Integer call() throws IOException, InterruptedException {
      WebServer.Config config =
          new WebServer.Config(
              host,
              port,
              workspace(spec, dataset, output),
              decisions.settings(),
              demo,
              Duration.ofMillis(demoPace),
              allowedHosts);
      CountDownLatch stopped = new CountDownLatch(1);
      WebServer server = WebServer.start(config);
      Runtime.getRuntime()
          .addShutdownHook(
              Thread.ofPlatform()
                  .unstarted(
                      () -> {
                        server.close();
                        stopped.countDown();
                      }));
      PrintWriter out = spec.commandLine().getOut();
      out.println("sonar web interface at " + server.uri() + (demo ? " (demo runs)" : ""));
      out.println("dataset " + config.workspace().dataset());
      out.println("output " + config.workspace().output());
      if (!server.bundled()) {
        out.println(
            "the interface is not bundled; run scripts/ui.sh build and restart, or use scripts/ui.sh dev");
      }
      stopped.await();
      return 0;
    }
  }
}
