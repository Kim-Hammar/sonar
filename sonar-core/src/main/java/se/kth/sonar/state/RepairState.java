package se.kth.sonar.state;

import java.util.Objects;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.verify.Verification;

/**
 * Where a repair run stands: the current configurations, their verification, and their program.
 *
 * @param problem the problem being repaired
 * @param snapshot the current configurations
 * @param verification the verification of the current configurations
 * @param program the program model of the current configurations
 * @param epoch the number of adoptions that led to this state
 */
public record RepairState(
    RepairProblem problem,
    Snapshot snapshot,
    Verification verification,
    ProgramModel program,
    int epoch) {

  /** Checks that the verification and the program describe the snapshot. */
  public RepairState {
    Objects.requireNonNull(problem, "problem");
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(verification, "verification");
    Objects.requireNonNull(program, "program");
    if (!verification.snapshot().equals(snapshot.id()) || !program.snapshot().equals(snapshot)) {
      throw new IllegalArgumentException(
          "the verification and the program must describe the snapshot");
    }
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch must not be negative: " + epoch);
    }
  }

  /** Returns the state of the unrepaired network. */
  public static RepairState initial(
      RepairProblem problem, Verification verification, ProgramModel program) {
    return new RepairState(problem, problem.initial(), verification, program, 0);
  }

  /** Returns the state after adopting a repair that produced {@code snapshot}. */
  public RepairState advance(Snapshot snapshot, Verification verification, ProgramModel program) {
    return new RepairState(problem, snapshot, verification, program, epoch + 1);
  }

  /** Returns whether every specification holds as required. */
  public boolean solved() {
    return verification.verdict().solved();
  }
}
