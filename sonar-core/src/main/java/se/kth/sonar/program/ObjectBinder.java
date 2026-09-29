package se.kth.sonar.program;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.text.RouterId;

/**
 * Turns parsed statements into typed objects as the relation schema declares them.
 *
 * <p>Each statement of a source production defines an object, identified by its router and key slot
 * values; statements of several sources with the same key define one object. A statement binds to
 * the object whose block contains it, to the object it defines, or to the object that its key
 * slots, or its {@code by} slots, name on the same router. Every other statement binds to the
 * router. Attribute values are read from the bound statements that meet the attribute's conditions.
 * Derived attributes are left to {@link ProgramModel.Factory#derive}.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class ObjectBinder {
  private final RelationSchema schema;

  /** Creates a binder for {@code schema}. */
  public ObjectBinder(RelationSchema schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  /** Returns the objects defined by the statements of every router, in router and line order. */
  public List<ProgramObject> bind(Map<RouterId, List<Statement>> statements) {
    throw new NotImplemented("ObjectBinder.bind");
  }
}
