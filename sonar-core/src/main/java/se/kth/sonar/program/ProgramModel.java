package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;

/** The program of one snapshot: its statements, typed objects, and relation instances. */
public final class ProgramModel {
  private final Snapshot snapshot;
  private final RelationSchema schema;
  private final Map<RouterId, List<Statement>> statements;
  private final Map<String, List<ProgramObject>> objectsByType;
  private final Map<ProgramObject.Ref, ProgramObject> objectsByRef;
  private final List<RelationInstance> instances;

  /** Creates a model; {@link ProgramService} is the usual way to build one. */
  public ProgramModel(
      Snapshot snapshot,
      RelationSchema schema,
      Map<RouterId, List<Statement>> statements,
      Collection<ProgramObject> objects,
      List<RelationInstance> instances) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.schema = Objects.requireNonNull(schema, "schema");
    Map<RouterId, List<Statement>> statementCopy = new LinkedHashMap<>();
    statements.forEach((router, list) -> statementCopy.put(router, List.copyOf(list)));
    this.statements = Collections.unmodifiableMap(statementCopy);
    Map<String, List<ProgramObject>> byType = new LinkedHashMap<>();
    Map<ProgramObject.Ref, ProgramObject> byRef = new HashMap<>();
    for (ProgramObject object : objects) {
      byType.computeIfAbsent(object.ref().type(), type -> new ArrayList<>()).add(object);
      if (byRef.put(object.ref(), object) != null) {
        throw new IllegalArgumentException("duplicate object " + object.ref());
      }
    }
    Map<String, List<ProgramObject>> frozen = new LinkedHashMap<>();
    byType.forEach((type, list) -> frozen.put(type, List.copyOf(list)));
    this.objectsByType = Collections.unmodifiableMap(frozen);
    this.objectsByRef = Collections.unmodifiableMap(byRef);
    this.instances = List.copyOf(instances);
  }

  /** Returns the snapshot the model describes. */
  public Snapshot snapshot() {
    return snapshot;
  }

  /** Returns the schema that typed the objects. */
  public RelationSchema schema() {
    return schema;
  }

  /** Returns the statements of {@code router} in line order. */
  public List<Statement> statements(RouterId router) {
    return statements.getOrDefault(router, List.of());
  }

  /** Returns the objects of type {@code type}. */
  public List<ProgramObject> objects(String type) {
    return objectsByType.getOrDefault(type, List.of());
  }

  /** Returns the object identified by {@code ref}. */
  public Optional<ProgramObject> object(ProgramObject.Ref ref) {
    return Optional.ofNullable(objectsByRef.get(ref));
  }

  /** Returns every relation instance. */
  public List<RelationInstance> instances() {
    return instances;
  }

  /** Returns the instances of the relation named {@code relation}. */
  public List<RelationInstance> instances(String relation) {
    return instances.stream().filter(instance -> instance.relation().equals(relation)).toList();
  }

  /** Returns the instances that violate at least one requirement. */
  public List<RelationInstance> violated() {
    return instances.stream().filter(instance -> !instance.satisfied()).toList();
  }

  /**
   * Computes what needs the semantics of the configuration language: facts per router and the
   * values of derived attributes. The Batfish adapter implements it.
   */
  public interface Factory {
    /** Returns the names of the derived-attribute functions this factory computes. */
    Set<String> functions();

    /**
     * Parses one configuration. The result depends only on the text, so callers cache it by digest.
     * Implementations are safe to call from several threads.
     */
    RouterFacts parse(RouterId router, ConfigText text);

    /**
     * Computes the derived attributes of {@code objects}, keyed by object and then by attribute,
     * from the facts of every router.
     */
    Map<ProgramObject.Ref, Map<String, List<String>>> derive(
        Topology topology, Map<RouterId, RouterFacts> facts, List<ProgramObject> objects);
  }

  /** What a factory learned from one configuration. */
  public interface RouterFacts {
    /** Returns the router. */
    RouterId router();

    /** Returns the digest of the configuration text the facts were parsed from. */
    ContentHash source();
  }
}
