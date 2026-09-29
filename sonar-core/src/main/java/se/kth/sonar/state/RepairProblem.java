package se.kth.sonar.state;

import java.util.Objects;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.spec.SpecificationSet;

/**
 * A network to repair.
 *
 * @param name the scenario's name
 * @param initial the faulty configurations
 * @param topology the physical links
 * @param specifications the specifications the repaired network must meet
 */
public record RepairProblem(
    String name, Snapshot initial, Topology topology, SpecificationSet specifications) {

  /** Checks the components. */
  public RepairProblem {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(initial, "initial");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(specifications, "specifications");
  }
}
