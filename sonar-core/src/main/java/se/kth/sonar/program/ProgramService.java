package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

/**
 * Builds the program model of a snapshot: the grammar parses each configuration, the binder types
 * the statements, the factory derives semantic attributes, and the evaluator checks the relations.
 *
 * <p>Parsing and binding run per router on the compute lane of the execution context, and their
 * results are cached by router and text digest, so after any change only the changed routers are
 * parsed and bound again; deriving and evaluating always run over the whole network.
 */
public final class ProgramService {
  private static final int CACHE_CAPACITY = 4096;

  private final Dialect dialect;
  private final ProgramModel.Factory factory;
  private final ExecutionContext context;
  private final ObjectBinder binder;
  private final RelationEvaluator evaluator;
  private final ContentCache<RouterModel> routers = new ContentCache<>(CACHE_CAPACITY);

  private record RouterModel(
      List<Statement> statements, List<ProgramObject> objects, List<SourceSpan> dangling) {}

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

  /** Returns the dialect the service models programs in. */
  public Dialect dialect() {
    return dialect;
  }

  /** Returns the factory that derives semantic attributes. */
  public ProgramModel.Factory factory() {
    return factory;
  }

  /** Returns the evaluator of the dialect's relations. */
  public RelationEvaluator evaluator() {
    return evaluator;
  }

  /** Returns the program model of {@code snapshot}. */
  public ProgramModel model(Snapshot snapshot, Topology topology) {
    return model(snapshot, topology, Optional.empty());
  }

  /**
   * Returns the program model of {@code snapshot}, reusing what {@code previous} established for
   * the routers whose text is unchanged, so that a change to a few routers costs little.
   */
  public ProgramModel model(Snapshot snapshot, Topology topology, Optional<ProgramModel> previous) {
    ProgramModel earlier = previous.filter(model -> model.topology().equals(topology)).orElse(null);
    if (earlier != null && earlier.snapshot().equals(snapshot)) {
      return earlier;
    }
    List<RouterId> ids = new ArrayList<>(snapshot.routers());
    List<RouterId> stale =
        earlier == null
            ? ids
            : ids.stream().filter(router -> !unchanged(router, snapshot, earlier)).toList();
    List<RouterModel> models = context.compute(stale, router -> routerModel(router, snapshot));
    Map<RouterId, RouterModel> fresh = new LinkedHashMap<>();
    for (int at = 0; at < stale.size(); at++) {
      fresh.put(stale.get(at), models.get(at));
    }
    Map<RouterId, List<Statement>> statements = new LinkedHashMap<>();
    List<ProgramObject> bound = new ArrayList<>();
    List<SourceSpan> dangling = new ArrayList<>();
    for (RouterId router : ids) {
      RouterModel model = fresh.get(router);
      if (model != null) {
        statements.put(router, model.statements());
        bound.addAll(model.objects());
        dangling.addAll(model.dangling());
      } else {
        statements.put(router, earlier.statements(router));
        bound.addAll(earlier.objectsOf(router));
        earlier.dangling().stream()
            .filter(span -> span.router().equals(router))
            .forEach(dangling::add);
      }
    }
    Map<ProgramObject.Ref, Map<String, List<String>>> derived =
        factory.derive(topology, statements, bound);
    List<ProgramObject> objects = new ArrayList<>(bound.size());
    Set<ProgramObject.Ref> changed = new HashSet<>();
    Set<ProgramObject.Ref> seen = new HashSet<>();
    for (ProgramObject object : bound) {
      Map<String, List<String>> extra = derived.getOrDefault(object.ref(), Map.of());
      ProgramObject complete = holdsAll(object, extra) ? object : withDerived(object, extra);
      ProgramObject before = earlier == null ? null : earlier.object(complete.ref()).orElse(null);
      if (before != null && before.attributes().equals(complete.attributes())) {
        if (before.statements().equals(complete.statements())) {
          complete = before;
        }
      } else {
        changed.add(complete.ref());
      }
      objects.add(complete);
      seen.add(complete.ref());
    }
    List<RelationInstance> instances;
    ObjectIndex index;
    if (earlier == null) {
      index = new ObjectIndex(objects);
      instances = evaluator.evaluate(index);
    } else {
      for (RouterId router : stale) {
        for (ProgramObject object : earlier.objectsOf(router)) {
          if (!seen.contains(object.ref())) {
            changed.add(object.ref());
          }
        }
      }
      for (RouterId router : earlier.snapshot().routers()) {
        if (!snapshot.contains(router)) {
          earlier.objectsOf(router).forEach(object -> changed.add(object.ref()));
        }
      }
      Set<String> changedTypes = new HashSet<>();
      changed.forEach(ref -> changedTypes.add(ref.type()));
      index = new ObjectIndex(objects, earlier.index(), changedTypes);
      instances = evaluator.reevaluate(index, earlier.instances(), changed);
    }
    return new ProgramModel(
        snapshot, topology, dialect.schema(), statements, index, instances, dangling);
  }

  /** Returns whether {@code object} already carries every derived value in {@code extra}. */
  private static boolean holdsAll(ProgramObject object, Map<String, List<String>> extra) {
    for (Map.Entry<String, List<String>> entry : extra.entrySet()) {
      if (!object.values(entry.getKey()).equals(entry.getValue())) {
        return false;
      }
    }
    return true;
  }

  private static boolean unchanged(RouterId router, Snapshot snapshot, ProgramModel earlier) {
    return earlier.snapshot().contains(router)
        && earlier.snapshot().text(router).digest().equals(snapshot.text(router).digest());
  }

  /** Returns the statements of {@code router}'s configuration in {@code snapshot}. */
  public List<Statement> parse(RouterId router, Snapshot snapshot) {
    return routerModel(router, snapshot).statements();
  }

  private RouterModel routerModel(RouterId router, Snapshot snapshot) {
    ContentHash key =
        ContentHash.builder().add(router.name()).add(snapshot.text(router).digest()).build();
    return routers.get(
        key,
        () -> {
          List<Statement> statements = dialect.grammar().parse(router, snapshot.text(router));
          ObjectBinder.Bound bound = binder.bind(router, statements);
          return new RouterModel(statements, bound.objects(), bound.dangling());
        });
  }

  private ProgramObject withDerived(ProgramObject object, Map<String, List<String>> extra) {
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    for (String name :
        dialect.schema().object(object.ref().type()).orElseThrow().attributes().keySet()) {
      List<String> values = extra.containsKey(name) ? extra.get(name) : object.values(name);
      if (!values.isEmpty()) {
        attributes.put(name, values);
      }
    }
    return new ProgramObject(object.ref(), attributes, object.statements());
  }
}
