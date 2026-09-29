package se.kth.sonar.generate;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.relation.RelationSchema;

/**
 * Completes a seed into hypotheses that keep the relations it touches, so that one hypothesis
 * repairs every side of a fault, such as both ends of a BGP session.
 *
 * <p>After applying the seed, every relation instance that involves a changed object is checked. A
 * violated hard requirement is completed: {@code exists} inserts the missing statement, {@code
 * equal} and {@code agree} align the other side, and {@code resolves} renames the reference or
 * creates the referenced object; {@code distinct} has no completion and discards the seed. A
 * violated soft requirement adds the completed hypothesis as an alternative. Completion repeats
 * until nothing changes, and stops at the bounds; each hypothesis is minimal, since no operator can
 * be dropped without violating a hard requirement.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class ClosureEngine {
  private final RelationSchema schema;
  private final Bounds bounds;

  /** Creates an engine for {@code schema}. */
  public ClosureEngine(RelationSchema schema, Bounds bounds) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.bounds = Objects.requireNonNull(bounds, "bounds");
  }

  /** Returns the completed hypotheses of {@code seed}, required ones first. */
  public List<Hypothesis> close(ProgramModel program, List<EditOperator> seed) {
    throw new NotImplemented("ClosureEngine.close");
  }
}
