package se.kth.sonar.engine;

import se.kth.sonar.report.RepairResult;
import se.kth.sonar.state.RepairProblem;

/** Repairs one network; {@link RepairEngine} is the real implementation. */
@FunctionalInterface
public interface Repairer {
  /** Repairs {@code problem} within the budget of the current run scope. */
  RepairResult repair(RepairProblem problem);
}
