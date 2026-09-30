package se.kth.sonar.program;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.grammar.Symbol;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

/** The program of one snapshot: its statements, typed objects, and relation instances. */
public final class ProgramModel {
  private final Snapshot snapshot;
  private final Topology topology;
  private final RelationSchema schema;
  private final Map<RouterId, List<Statement>> statements;
  private final ObjectIndex index;
  private final List<RelationInstance> instances;
  private final List<SourceSpan> dangling;

  /** Creates a model; {@link ProgramService} is the usual way to build one. */
  public ProgramModel(
      Snapshot snapshot,
      Topology topology,
      RelationSchema schema,
      Map<RouterId, List<Statement>> statements,
      Collection<ProgramObject> objects,
      List<RelationInstance> instances,
      List<SourceSpan> dangling) {
    this(snapshot, topology, schema, statements, new ObjectIndex(objects), instances, dangling);
  }

  ProgramModel(
      Snapshot snapshot,
      Topology topology,
      RelationSchema schema,
      Map<RouterId, List<Statement>> statements,
      ObjectIndex index,
      List<RelationInstance> instances,
      List<SourceSpan> dangling) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.topology = Objects.requireNonNull(topology, "topology");
    this.schema = Objects.requireNonNull(schema, "schema");
    this.statements = Collections.unmodifiableMap(new LinkedHashMap<>(statements));
    this.index = index;
    this.instances = List.copyOf(instances);
    this.dangling = List.copyOf(dangling);
  }

  /** Returns the index of the model's objects. */
  ObjectIndex index() {
    return index;
  }

  /** Returns the snapshot the model describes. */
  public Snapshot snapshot() {
    return snapshot;
  }

  /** Returns the topology the snapshot is deployed in. */
  public Topology topology() {
    return topology;
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
    return index.ofType(type);
  }

  /** Returns every object, in router and line order. */
  public List<ProgramObject> objects() {
    return index.all();
  }

  /** Returns the objects of {@code router}, its own object first. */
  public List<ProgramObject> objectsOf(RouterId router) {
    return index.ofRouter(router);
  }

  /** Returns the object identified by {@code ref}. */
  public Optional<ProgramObject> object(ProgramObject.Ref ref) {
    return Optional.ofNullable(index.get(ref));
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
   * Returns the objects on {@code routers} whose attributes differ between {@code before} and
   * {@code after}, including objects only one of them has.
   */
  public static Set<ProgramObject.Ref> changedObjects(
      ProgramModel before, ProgramModel after, Collection<RouterId> routers) {
    Set<ProgramObject.Ref> changed = new LinkedHashSet<>();
    for (RouterId router : new LinkedHashSet<>(routers)) {
      Map<ProgramObject.Ref, ProgramObject> earlier = new HashMap<>();
      before.objectsOf(router).forEach(object -> earlier.put(object.ref(), object));
      for (ProgramObject object : after.objectsOf(router)) {
        ProgramObject old = earlier.remove(object.ref());
        if (old == null || !old.attributes().equals(object.attributes())) {
          changed.add(object.ref());
        }
      }
      changed.addAll(earlier.keySet());
    }
    return changed;
  }

  /** Returns the statements that name an object which does not exist, in router and line order. */
  public List<SourceSpan> dangling() {
    return dangling;
  }

  /**
   * Computes what needs the semantics of the configuration language: the values of derived
   * attributes, and how to realise a wanted value of a derived attribute in configuration text.
   * Implementations are safe to call from several threads.
   */
  public interface Factory {
    /** Returns the names of the derived-attribute functions this factory computes. */
    Set<String> functions();

    /**
     * Computes the derived attributes of {@code objects}, keyed by object and then by attribute,
     * from the statements of every router and the topology. A reference to an object on another
     * router is written as that object's {@link ProgramObject.Ref#toString() reference text}.
     */
    Map<ProgramObject.Ref, Map<String, List<String>>> derive(
        Topology topology, Map<RouterId, List<Statement>> statements, List<ProgramObject> objects);

    /**
     * Returns the ways to give the derived attribute computed by {@code function} the value {@code
     * target} on {@code object}: each is a statement to insert into the object's block, or to
     * replace an existing statement of the same production with. An empty list means the value
     * cannot be realised by editing the object.
     */
    List<Realisation> realise(
        String function, String target, ProgramObject object, ProgramModel program);
  }

  /**
   * A statement that realises a wanted derived value.
   *
   * @param production the production of the statement
   * @param values the values of the slots the realisation fixes; the others are left to the slots'
   *     domains
   * @param holder the object whose statements the realisation edits, when it is not the object that
   *     carries the derived attribute, as when a value is inherited from another object
   * @param statement the one existing statement to change, when the value is realised by changing a
   *     particular statement rather than any statement of the production
   */
  public record Realisation(
      Production production,
      Map<String, List<String>> values,
      Optional<ProgramObject.Ref> holder,
      Optional<SourceSpan> statement) {
    /** Copies the values and checks that each belongs to its slot's sort. */
    public Realisation {
      Objects.requireNonNull(production, "production");
      Objects.requireNonNull(holder, "holder");
      Objects.requireNonNull(statement, "statement");
      SequencedMap<String, List<String>> copy = new LinkedHashMap<>();
      values.forEach(
          (slot, tokens) -> {
            Symbol.Slot declared =
                production
                    .slot(slot)
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                "production " + production.id() + " has no slot " + slot));
            for (String token : tokens) {
              if (!declared.accepts(token)) {
                throw new IllegalArgumentException(
                    "'" + token + "' is not a " + declared.sort().name() + " for " + slot);
              }
            }
            copy.put(slot, List.copyOf(tokens));
          });
      values = Collections.unmodifiableSequencedMap(copy);
    }

    /** A realisation that edits the object itself. */
    public Realisation(Production production, Map<String, List<String>> values) {
      this(production, values, Optional.empty(), Optional.empty());
    }

    /** A realisation that edits {@code holder}'s statements. */
    public Realisation(
        Production production, Map<String, List<String>> values, ProgramObject.Ref holder) {
      this(production, values, Optional.of(holder), Optional.empty());
    }

    /** A realisation that changes the statement at {@code statement} of {@code holder}. */
    public Realisation(
        Production production,
        Map<String, List<String>> values,
        ProgramObject.Ref holder,
        SourceSpan statement) {
      this(production, values, Optional.of(holder), Optional.of(statement));
    }
  }
}
