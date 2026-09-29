package se.kth.sonar.program;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.relation.RelationSchema;

/**
 * Finds the instances of every relation among a program's objects and checks their requirements.
 *
 * <p>The driving role ranges over the objects of its type for which every join path is defined.
 * Each further role is filled by the object that satisfies the joins, or stays empty. The instances
 * of a symmetric relation are unordered pairs, and each requirement is checked with the roles both
 * ways round. A guarded requirement applies only when its guard has a value. {@code distinct}
 * compares values across all instances of the relation.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class RelationEvaluator {
  private final RelationSchema schema;

  /** Creates an evaluator for {@code schema}. */
  public RelationEvaluator(RelationSchema schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  /** Returns the instances of every relation, in schema order, with their violations. */
  public List<RelationInstance> evaluate(Collection<ProgramObject> objects) {
    throw new NotImplemented("RelationEvaluator.evaluate");
  }
}
