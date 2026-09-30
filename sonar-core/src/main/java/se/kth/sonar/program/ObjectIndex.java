package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import se.kth.sonar.text.RouterId;

/**
 * The objects of a program, indexed by type, router, reference and attribute value; shared by a
 * model and the evaluation of its relations. The value indexes are built when first asked for, and
 * an index built from an earlier one keeps the value indexes of the types whose objects are the
 * same.
 */
final class ObjectIndex {
  private final List<ProgramObject> all;
  private final Map<String, List<ProgramObject>> byType;
  private final Map<ProgramObject.Ref, ProgramObject> byRef;
  private final Map<RouterId, List<ProgramObject>> byRouter;
  private final Map<String, Map<String, Map<List<String>, List<ProgramObject>>>> byValue =
      new ConcurrentHashMap<>();

  /**
   * Indexes {@code objects}.
   *
   * @throws IllegalArgumentException if two objects share a reference
   */
  ObjectIndex(Collection<ProgramObject> objects) {
    this.all = List.copyOf(objects);
    Map<String, List<ProgramObject>> type = new LinkedHashMap<>();
    Map<ProgramObject.Ref, ProgramObject> ref = new HashMap<>(all.size() * 2);
    Map<RouterId, List<ProgramObject>> router = new LinkedHashMap<>();
    for (ProgramObject object : all) {
      type.computeIfAbsent(object.ref().type(), t -> new ArrayList<>()).add(object);
      router.computeIfAbsent(object.ref().router(), r -> new ArrayList<>()).add(object);
      if (ref.put(object.ref(), object) != null) {
        throw new IllegalArgumentException("duplicate object " + object.ref());
      }
    }
    type.replaceAll((t, list) -> List.copyOf(list));
    router.replaceAll((r, list) -> List.copyOf(list));
    this.byType = Collections.unmodifiableMap(type);
    this.byRef = Collections.unmodifiableMap(ref);
    this.byRouter = Collections.unmodifiableMap(router);
  }

  /**
   * Indexes {@code objects} like {@link #ObjectIndex(Collection)}, keeping the value indexes that
   * {@code previous} built for every type not in {@code changedTypes}.
   */
  ObjectIndex(Collection<ProgramObject> objects, ObjectIndex previous, Set<String> changedTypes) {
    this(objects);
    previous.byValue.forEach(
        (type, indexes) -> {
          if (!changedTypes.contains(type)) {
            byValue.put(type, indexes);
          }
        });
  }

  List<ProgramObject> all() {
    return all;
  }

  List<ProgramObject> ofType(String type) {
    return byType.getOrDefault(type, List.of());
  }

  List<ProgramObject> ofRouter(RouterId router) {
    return byRouter.getOrDefault(router, List.of());
  }

  ProgramObject get(ProgramObject.Ref ref) {
    return byRef.get(ref);
  }

  /** Returns the objects of {@code type} whose {@code attribute} has exactly {@code value}. */
  List<ProgramObject> withValue(String type, String attribute, List<String> value) {
    Map<List<String>, List<ProgramObject>> index =
        byValue
            .computeIfAbsent(type, t -> new ConcurrentHashMap<>())
            .computeIfAbsent(attribute, a -> valueIndex(type, a));
    return index.getOrDefault(value, List.of());
  }

  private Map<List<String>, List<ProgramObject>> valueIndex(String type, String attribute) {
    Map<List<String>, List<ProgramObject>> map = new HashMap<>();
    for (ProgramObject object : ofType(type)) {
      List<String> values = object.values(attribute);
      if (!values.isEmpty()) {
        map.computeIfAbsent(values, v -> new ArrayList<>(1)).add(object);
      }
    }
    map.replaceAll((v, list) -> List.copyOf(list));
    return Map.copyOf(map);
  }
}
