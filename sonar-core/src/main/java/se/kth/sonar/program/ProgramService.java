package se.kth.sonar.program;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.Dialect;

/**
 * Builds the program model of a snapshot: the grammar parses each configuration, the binder types
 * the statements, the factory derives semantic attributes, and the evaluator checks the relations.
 *
 * <p>Parsing runs per router on the compute lane of the execution context, and parse results are
 * cached by text digest, so after a repair only the changed routers are parsed again.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class ProgramService {
  private final Dialect dialect;
  private final ProgramModel.Factory factory;
  private final ExecutionContext context;
  private final ObjectBinder binder;
  private final RelationEvaluator evaluator;
  private final ContentCache<ProgramModel.RouterFacts> facts = new ContentCache<>();

  /**
   * Creates a service for {@code dialect}.
   *
   * @throws IllegalArgumentException if the factory cannot compute a derived attribute that the
   *     dialect's schema declares
   */
  public ProgramService(Dialect dialect, ProgramModel.Factory factory, ExecutionContext context) {
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.factory = Objects.requireNonNull(factory, "factory");
    this.context = Objects.requireNonNull(context, "context");
    Set<String> missing = new TreeSet<>(dialect.schema().derivedFunctions());
    missing.removeAll(factory.functions());
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("the program model factory does not compute " + missing);
    }
    this.binder = new ObjectBinder(dialect.schema());
    this.evaluator = new RelationEvaluator(dialect.schema());
  }

  /** Returns the program model of {@code snapshot}. */
  public ProgramModel model(Snapshot snapshot, Topology topology) {
    throw new NotImplemented("ProgramService.model");
  }
}
