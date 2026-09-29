package se.kth.sonar.cli;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
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
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.engine.BenchmarkRunner;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.web.WebServer;

/** The {@code sonar} command line. */
@Command(
    name = "sonar",
    mixinStandardHelpOptions = true,
    versionProvider = SonarCli.Version.class,
    description = "SONAR: Search-Optimal Network Agentic Repair.",
    subcommands = {SonarCli.Repair.class, SonarCli.Serve.class})
public final class SonarCli implements Runnable {
  static final int NOT_IMPLEMENTED = 3;

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

    HttpTransport.Settings settings() {
      HttpTransport.Settings defaults = HttpTransport.Settings.openRouter();
      return new HttpTransport.Settings(
          endpoint, model, defaults.apiKeyVariable(), defaults.timeout());
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
        required = true,
        paramLabel = "DIR",
        description = "Dataset directory with one directory per scenario.")
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
        required = true,
        paramLabel = "DIR",
        description = "Directory for run logs, the decision cache and engine storage.")
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
        names = "--max-verifications",
        paramLabel = "N",
        description = "Verifications per scenario.")
    private Long maxVerifications;

    @Option(names = "--max-decisions", paramLabel = "N", description = "Decisions per scenario.")
    private Long maxDecisions;

    @Option(
        names = "--max-input-tokens",
        paramLabel = "N",
        description = "Input tokens per scenario.")
    private Long maxInputTokens;

    @Option(
        names = "--time-limit",
        paramLabel = "SECONDS",
        description = "Time limit per scenario.")
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

    @Option(
        names = "--prefetch-decisions",
        defaultValue = "0",
        paramLabel = "N",
        description = "Decisions requested ahead of need (default: ${DEFAULT-VALUE}).")
    private int prefetchDecisions;

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
              new ParallelismConfig(
                  verificationWorkers,
                  decisionConcurrency,
                  generationParallelism,
                  scenarioParallelism,
                  prefetchDecisions),
              limits,
              Optional.ofNullable(timeLimit).map(Duration::ofSeconds),
              seed);
      Workspace workspace = new Workspace(dataset, output);
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
        required = true,
        paramLabel = "DIR",
        description = "Dataset directory with one directory per scenario.")
    private Path dataset;

    @Option(
        names = "--output",
        required = true,
        paramLabel = "DIR",
        description = "Directory for run logs, the decision cache and engine storage.")
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
              new Workspace(dataset, output),
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
      if (!server.bundled()) {
        out.println(
            "the interface is not bundled; run scripts/ui.sh build and restart, or use scripts/ui.sh dev");
      }
      stopped.await();
      return 0;
    }
  }
}
