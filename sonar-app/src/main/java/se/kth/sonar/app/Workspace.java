package se.kth.sonar.app;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where runs read scenarios and write their results.
 *
 * @param dataset the benchmark dataset, with one directory per scenario
 * @param output the directory that receives run logs, the decision cache and engine storage
 */
public record Workspace(Path dataset, Path output) {
  /** Makes both paths absolute. */
  public Workspace {
    dataset = Objects.requireNonNull(dataset, "dataset").toAbsolutePath().normalize();
    output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
  }

  /** Returns the directory that holds one directory per run. */
  public Path runs() {
    return output.resolve("runs");
  }

  /** Returns the directory of the decision cache. */
  public Path decisionCache() {
    return output.resolve("decision-cache");
  }

  /** Returns the private storage directory of data-plane engine {@code index}. */
  public Path engineStorage(int index) {
    return output.resolve("batfish").resolve(Integer.toString(index));
  }
}
