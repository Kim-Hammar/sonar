package se.kth.sonar.batfish;

import java.nio.file.Path;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.verify.DataPlaneEngine;

/**
 * Computes data planes with Batfish running inside this process, with no server and no container.
 *
 * <p>An analysis writes the snapshot's configurations to the engine's private storage, parses them,
 * computes the data plane, and evaluates the specified predicates with the forwarding analysis
 * questions of the benchmark's Batfish build; the snapshot is then deleted. Parse results of
 * unchanged configurations are reused between analyses. An engine holds one Batfish instance and
 * serves one thread at a time.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class BatfishEngine implements DataPlaneEngine {
  private final Settings settings;

  /**
   * Settings of one engine.
   *
   * @param storage a directory for Batfish's storage, used by this engine only
   */
  public record Settings(Path storage) {
    /** Checks the storage directory. */
    public Settings {
      Objects.requireNonNull(storage, "storage");
    }
  }

  /** Creates an engine; Batfish starts on the first analysis. */
  public BatfishEngine(Settings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public Observation analyze(Snapshot snapshot, SpecificationSet specifications) {
    throw new NotImplemented("BatfishEngine.analyze");
  }

  @Override
  public void close() {}
}
