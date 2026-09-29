package se.kth.sonar.web.runs;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import se.kth.sonar.app.Pipeline;
import se.kth.sonar.app.RunDocument;
import se.kth.sonar.app.Workspace;
import se.kth.sonar.engine.BenchmarkRunner;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.jev.HttpTransport;
import se.kth.sonar.trace.TraceSink;

/**
 * Runs the agent through a {@link Pipeline} opened for each run: the repair engine, or the scripted
 * repairer for demonstration runs.
 */
public final class PipelineLauncher implements RunLauncher {
  private final Workspace workspace;
  private final HttpTransport.Settings decisions;
  private final Duration demoPace;

  /** Creates a launcher for {@code workspace} that asks the decision model at {@code decisions}. */
  public PipelineLauncher(
      Workspace workspace, HttpTransport.Settings decisions, Duration demoPace) {
    this.workspace = Objects.requireNonNull(workspace, "workspace");
    this.decisions = Objects.requireNonNull(decisions, "decisions");
    this.demoPace = Objects.requireNonNull(demoPace, "demoPace");
  }

  @Override
  public List<BenchmarkRunner.Outcome> launch(
      RunDocument run, Cancellation cancellation, TraceSink sink) {
    Pipeline.Agent agent =
        run.demo() ? new Pipeline.Agent.Scripted(demoPace) : new Pipeline.Agent.Engine(decisions);
    try (Pipeline pipeline = Pipeline.open(workspace, run.settings().toRunSettings(), agent)) {
      return pipeline.run(run.scenarios(), cancellation, sink);
    }
  }
}
