package se.kth.sonar.batfish;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.verify.DataPlaneEngine;

/**
 * Computes the data plane of a snapshot and reports the forwarding table it was judged on, besides
 * which specified predicates hold. An inspector is used by one thread at a time.
 */
public interface DataPlaneInspector extends AutoCloseable {
  /** Analyses {@code snapshot} against {@code specifications} and returns the full report. */
  Report inspect(Snapshot snapshot, SpecificationSet specifications);

  /** Releases the inspector's resources. */
  @Override
  void close();

  /**
   * What one analysis observed, with the forwarding table it was mined from.
   *
   * @param observation the predicates that hold and the evidence
   * @param forwarding the forwarding table, in router, VRF and route order
   */
  record Report(DataPlaneEngine.Observation observation, List<ForwardingEntry> forwarding) {
    /** Checks the observation and copies the table. */
    public Report {
      Objects.requireNonNull(observation, "observation");
      forwarding = List.copyOf(forwarding);
    }
  }
}
