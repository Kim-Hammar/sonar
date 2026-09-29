package se.kth.sonar.generate;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.state.RepairState;

/**
 * Rejects candidates that cannot be repairs before any of them is verified: patches that do not
 * apply, text that the grammar cannot parse, new violations of hard requirements, changes to
 * nothing that the data plane depends on, and duplicates, meaning candidates that produce a
 * snapshot already produced by another candidate.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class Admissibility {
  private final ProgramService programs;

  /** Creates a filter that builds candidate programs with {@code programs}. */
  public Admissibility(ProgramService programs) {
    this.programs = Objects.requireNonNull(programs, "programs");
  }

  /** Returns the admissible candidates, in their original order. */
  public List<Hypothesis> admit(RepairState state, List<Hypothesis> candidates) {
    throw new NotImplemented("Admissibility.admit");
  }
}
