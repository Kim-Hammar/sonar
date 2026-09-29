package se.kth.sonar.web.runs;

import java.util.List;
import se.kth.sonar.app.RunDocument;
import se.kth.sonar.engine.BenchmarkRunner;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.trace.TraceSink;

/** Runs the agent for one run; the web server's port to the repair pipeline. */
@FunctionalInterface
public interface RunLauncher {
  /**
   * Repairs the scenarios of {@code run} with its settings, sending the trace to {@code sink}, and
   * returns the outcomes in scenario order.
   */
  List<BenchmarkRunner.Outcome> launch(RunDocument run, Cancellation cancellation, TraceSink sink);
}
