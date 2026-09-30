package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.relation.AttributePath;
import se.kth.sonar.relation.Constraint;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.relation.RelationType;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.text.RouterId;

/**
 * Finds the instances of every relation among a program's objects and checks their requirements.
 *
 * <p>The driving role ranges over the objects of its type for which every join path is defined.
 * Each further role is filled by the object that satisfies the joins, or stays empty. The instances
 * of a symmetric relation are unordered pairs, and each requirement is checked with the roles both
 * ways round. A guarded requirement applies only when its guard has a value, and a requirement on
 * the attributes of an unfilled role does not apply; only {@code exists} on the role itself does.
 * {@code distinct} compares values across all instances of the relation.
 */
public final class RelationEvaluator {
  private final RelationSchema schema;

  /** Creates an evaluator for {@code schema}. */
  public RelationEvaluator(RelationSchema schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  /**
   * Returns the values at {@code path} in {@code instance}, whose objects are looked up in {@code
   * program}, or nothing when the path is not defined there.
   */
  public Optional<List<String>> valueAt(
      ProgramModel program, RelationInstance instance, AttributePath path) {
    return values(path, roles(program, instance), new Index(program.index()));
  }

  /**
   * Returns the object that holds the last attribute of {@code path} in {@code instance}, if every
   * reference on the way resolves.
   */
  public Optional<ProgramObject> holderOf(
      ProgramModel program, RelationInstance instance, AttributePath path) {
    return holder(path, roles(program, instance), new Index(program.index()));
  }

  /**
   * Returns whether {@code requirement} holds for the roles of {@code instance} as given, without
   * trying the other way round of a symmetric relation.
   */
  public boolean holds(ProgramModel program, RelationInstance instance, Requirement requirement) {
    return holds(requirement, roles(program, instance), new Index(program.index()));
  }

  /**
   * Returns whether {@code requirement} applies to {@code instance}: its guard, if any, has a
   * value, and the roles its paths name are filled, so that it can hold or be violated rather than
   * be vacuous.
   */
  public boolean applies(ProgramModel program, RelationInstance instance, Requirement requirement) {
    Map<String, ProgramObject> roles = roles(program, instance);
    Index index = new Index(program.index());
    if (requirement.guard().isPresent() && !defined(requirement.guard().get(), roles, index)) {
      return false;
    }
    return switch (requirement.constraint()) {
      case Constraint.Exists exists ->
          exists.path().attributes().isEmpty() || roles.containsKey(exists.path().role());
      case Constraint.Absent absent -> roles.containsKey(absent.path().role());
      case Constraint.Equal equal ->
          roles.containsKey(equal.left().role()) && roles.containsKey(equal.right().role());
      case Constraint.Agree agree ->
          roles.containsKey(agree.left().role()) && roles.containsKey(agree.right().role());
      case Constraint.Resolves resolves -> roles.containsKey(resolves.path().role());
      case Constraint.Distinct distinct -> roles.containsKey(distinct.path().role());
    };
  }

  private static Map<String, ProgramObject> roles(ProgramModel program, RelationInstance instance) {
    Map<String, ProgramObject> roles = new LinkedHashMap<>();
    instance.roles().forEach((role, ref) -> program.object(ref).ifPresent(o -> roles.put(role, o)));
    return roles;
  }

  /** Returns the instances of every relation, in schema order, with their violations. */
  public List<RelationInstance> evaluate(Collection<ProgramObject> objects) {
    return evaluate(new ObjectIndex(objects));
  }

  List<RelationInstance> evaluate(ObjectIndex objects) {
    Index index = new Index(objects);
    List<RelationInstance> instances = new ArrayList<>();
    for (RelationType relation : schema.relations()) {
      instances.addAll(instances(relation, index, Map.of(), Set.of()));
    }
    return List.copyOf(instances);
  }

  /**
   * Returns the instances after the objects in {@code changed} changed, appeared or disappeared,
   * reusing every previous instance the change cannot affect: a relation is left alone when no
   * object of its role types changed and none of its instances read a changed object; otherwise its
   * tuples are formed again, and a tuple whose roles and reads are untouched keeps its violations.
   */
  public List<RelationInstance> reevaluate(
      Collection<ProgramObject> objects,
      List<RelationInstance> previous,
      Set<ProgramObject.Ref> changed) {
    return reevaluate(new ObjectIndex(objects), previous, changed);
  }

  List<RelationInstance> reevaluate(
      ObjectIndex objects, List<RelationInstance> previous, Set<ProgramObject.Ref> changed) {
    Index index = new Index(objects);
    Set<String> changedTypes = new HashSet<>();
    changed.forEach(ref -> changedTypes.add(ref.type()));
    Map<String, List<RelationInstance>> earlier = new HashMap<>();
    for (RelationInstance instance : previous) {
      earlier.computeIfAbsent(instance.relation(), r -> new ArrayList<>()).add(instance);
    }
    List<RelationInstance> instances = new ArrayList<>(previous.size());
    for (RelationType relation : schema.relations()) {
      List<RelationInstance> kept = earlier.getOrDefault(relation.name(), List.of());
      boolean touched =
          relation.roles().stream().anyMatch(role -> changedTypes.contains(role.object()));
      if (!touched && kept.stream().noneMatch(instance -> instance.dependsOn(changed))) {
        instances.addAll(kept);
        continue;
      }
      Map<ProgramObject.Ref, RelationInstance> byDriver = new HashMap<>(kept.size() * 2);
      kept.forEach(instance -> byDriver.put(instance.roles().values().iterator().next(), instance));
      instances.addAll(instances(relation, index, byDriver, changed));
    }
    return List.copyOf(instances);
  }

  /**
   * Forms the tuples of {@code relation} and checks them, taking the violations of a tuple from its
   * {@code previous} instance under the same driver when its roles are the same and it read none of
   * the {@code changed} objects.
   */
  private List<RelationInstance> instances(
      RelationType relation,
      Index index,
      Map<ProgramObject.Ref, RelationInstance> previous,
      Set<ProgramObject.Ref> changed) {
    List<Map<String, ProgramObject>> tuples = new ArrayList<>();
    List<RelationInstance> kept = new ArrayList<>();
    List<List<Requirement>> violations = new ArrayList<>();
    List<Set<ProgramObject.Ref>> reads = new ArrayList<>();
    Set<Set<ProgramObject.Ref>> seen = new HashSet<>();
    for (ProgramObject driver : index.ofType(relation.driver().object())) {
      index.drainReads();
      Optional<Map<String, ProgramObject>> roles = tuple(relation, driver, index);
      if (roles.isEmpty()) {
        continue;
      }
      if (relation.symmetric() && roles.get().size() == relation.roles().size()) {
        Set<ProgramObject.Ref> pair = new HashSet<>();
        roles.get().values().forEach(object -> pair.add(object.ref()));
        if (!seen.add(pair)) {
          continue;
        }
      }
      tuples.add(roles.get());
      RelationInstance before = previous.get(driver.ref());
      if (before != null && sameRoles(before, roles.get()) && !before.dependsOn(changed)) {
        kept.add(before);
        violations.add(withoutDistinct(before.violated()));
        reads.add(before.reads());
      } else {
        kept.add(null);
        Checked checked = check(relation, roles.get(), index);
        violations.add(checked.violated());
        reads.add(checked.reads());
      }
    }
    return finish(relation, tuples, kept, violations, reads, index);
  }

  private static boolean sameRoles(RelationInstance instance, Map<String, ProgramObject> roles) {
    if (instance.roles().size() != roles.size()) {
      return false;
    }
    for (Map.Entry<String, ProgramObject> entry : roles.entrySet()) {
      if (!entry.getValue().ref().equals(instance.roles().get(entry.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static List<Requirement> withoutDistinct(List<Requirement> violated) {
    List<Requirement> result = new ArrayList<>(violated.size());
    for (Requirement requirement : violated) {
      if (!(requirement.constraint() instanceof Constraint.Distinct)) {
        result.add(requirement);
      }
    }
    return result;
  }

  /** Returns the roles of the instance driven by {@code driver}, if its join paths are defined. */
  private Optional<Map<String, ProgramObject>> tuple(
      RelationType relation, ProgramObject driver, Index index) {
    Map<String, ProgramObject> roles = new LinkedHashMap<>();
    roles.put(relation.driver().name(), driver);
    return fill(relation, roles, index) ? Optional.of(roles) : Optional.empty();
  }

  private record Checked(List<Requirement> violated, Set<ProgramObject.Ref> reads) {}

  /**
   * Checks the requirements of one tuple, except {@code distinct}, recording what forming and
   * checking the tuple read and, so that every object the tuple could depend on is known, the
   * objects on every requirement path in both orientations.
   */
  private Checked check(RelationType relation, Map<String, ProgramObject> roles, Index index) {
    List<Requirement> violated = new ArrayList<>();
    for (Requirement requirement : relation.requirements()) {
      if (!(requirement.constraint() instanceof Constraint.Distinct)
          && violates(relation, requirement, roles, index)) {
        violated.add(requirement);
      }
    }
    follow(relation, roles, index);
    if (relation.symmetric() && roles.size() == 2) {
      follow(relation, swapped(roles), index);
    }
    Set<ProgramObject.Ref> reads = new HashSet<>(index.drainReads());
    roles.values().forEach(object -> reads.add(object.ref()));
    return new Checked(violated, reads);
  }

  /** Resolves every path of every requirement of {@code relation}, to record the reads. */
  private void follow(RelationType relation, Map<String, ProgramObject> roles, Index index) {
    for (Requirement requirement : relation.requirements()) {
      requirement.guard().ifPresent(path -> holder(path, roles, index));
      switch (requirement.constraint()) {
        case Constraint.Exists exists -> holder(exists.path(), roles, index);
        case Constraint.Absent absent -> holder(absent.path(), roles, index);
        case Constraint.Equal equal -> {
          holder(equal.left(), roles, index);
          holder(equal.right(), roles, index);
        }
        case Constraint.Agree agree -> {
          holder(agree.left(), roles, index);
          holder(agree.right(), roles, index);
        }
        case Constraint.Resolves resolves -> resolves(resolves.path(), roles, index);
        case Constraint.Distinct distinct -> holder(distinct.path(), roles, index);
      }
    }
  }

  private static Map<String, ProgramObject> swapped(Map<String, ProgramObject> roles) {
    List<String> names = List.copyOf(roles.keySet());
    Map<String, ProgramObject> swapped = new LinkedHashMap<>();
    swapped.put(names.get(0), roles.get(names.get(1)));
    swapped.put(names.get(1), roles.get(names.get(0)));
    return swapped;
  }

  /** Marks {@code distinct} violations across the tuples and builds or reuses the instances. */
  private List<RelationInstance> finish(
      RelationType relation,
      List<Map<String, ProgramObject>> tuples,
      List<RelationInstance> kept,
      List<List<Requirement>> violations,
      List<Set<ProgramObject.Ref>> reads,
      Index index) {
    for (Requirement requirement : relation.requirements()) {
      if (requirement.constraint() instanceof Constraint.Distinct distinct) {
        markDuplicates(distinct.path(), requirement, tuples, violations, index);
      }
    }
    List<RelationInstance> result = new ArrayList<>(tuples.size());
    for (int at = 0; at < tuples.size(); at++) {
      RelationInstance before = kept.get(at);
      if (before != null && before.violated().equals(violations.get(at))) {
        result.add(before);
        continue;
      }
      SequencedMap<String, ProgramObject.Ref> refs = new LinkedHashMap<>();
      tuples.get(at).forEach((role, object) -> refs.put(role, object.ref()));
      result.add(new RelationInstance(relation.name(), refs, violations.get(at), reads.get(at)));
    }
    return result;
  }

  private boolean fill(RelationType relation, Map<String, ProgramObject> roles, Index index) {
    for (RelationType.Role role : relation.roles().subList(1, relation.roles().size())) {
      List<ProgramObject> candidates = null;
      for (RelationType.Join join : relation.joins()) {
        AttributePath own;
        AttributePath other;
        if (join.left().role().equals(role.name())) {
          own = join.left();
          other = join.right();
        } else if (join.right().role().equals(role.name())) {
          own = join.right();
          other = join.left();
        } else {
          continue;
        }
        if (!roles.containsKey(other.role())) {
          continue;
        }
        Optional<List<String>> value = values(other, roles, index);
        if (value.isEmpty()) {
          return false;
        }
        if (candidates == null) {
          candidates = new ArrayList<>(matching(role, own, value.get(), roles, index));
        } else {
          candidates.removeIf(
              candidate -> !matches(candidate, role, own, value.get(), roles, index));
        }
      }
      if (candidates != null && !candidates.isEmpty()) {
        roles.put(role.name(), candidates.getFirst());
      }
    }
    return true;
  }

  /** Returns the objects that can fill {@code role} with {@code value} at its join path. */
  private List<ProgramObject> matching(
      RelationType.Role role,
      AttributePath own,
      List<String> value,
      Map<String, ProgramObject> roles,
      Index index) {
    if (own.attributes().isEmpty()) {
      RouterId router = roles.get(roles.keySet().iterator().next()).ref().router();
      return index.resolve(role.object(), router, value.getFirst()).map(List::of).orElse(List.of());
    }
    if (own.attributes().size() == 1) {
      return index.withValue(role.object(), own.attributes().getFirst(), value);
    }
    List<ProgramObject> result = new ArrayList<>();
    for (ProgramObject candidate : index.ofType(role.object())) {
      if (matches(candidate, role, own, value, roles, index)) {
        result.add(candidate);
      }
    }
    return result;
  }

  /** Returns whether {@code candidate} has {@code value} at the join path {@code own}. */
  private boolean matches(
      ProgramObject candidate,
      RelationType.Role role,
      AttributePath own,
      List<String> value,
      Map<String, ProgramObject> roles,
      Index index) {
    if (own.attributes().isEmpty()) {
      RouterId router = roles.get(roles.keySet().iterator().next()).ref().router();
      return index
          .resolve(role.object(), router, value.getFirst())
          .filter(resolved -> resolved.ref().equals(candidate.ref()))
          .isPresent();
    }
    if (own.attributes().size() == 1) {
      return candidate.values(own.attributes().getFirst()).equals(value);
    }
    Map<String, ProgramObject> trial = new HashMap<>(roles);
    trial.put(role.name(), candidate);
    return values(own, trial, index).filter(value::equals).isPresent();
  }

  private boolean violates(
      RelationType relation,
      Requirement requirement,
      Map<String, ProgramObject> roles,
      Index index) {
    if (holds(requirement, roles, index)) {
      if (!relation.symmetric() || roles.size() < 2) {
        return false;
      }
      return !holds(requirement, swapped(roles), index);
    }
    return true;
  }

  private boolean holds(Requirement requirement, Map<String, ProgramObject> roles, Index index) {
    if (requirement.guard().isPresent() && !defined(requirement.guard().get(), roles, index)) {
      return true;
    }
    return switch (requirement.constraint()) {
      case Constraint.Exists exists ->
          (!exists.path().attributes().isEmpty() && !roles.containsKey(exists.path().role()))
              || defined(exists.path(), roles, index);
      case Constraint.Absent absent -> !defined(absent.path(), roles, index);
      case Constraint.Equal equal -> {
        if (!roles.containsKey(equal.left().role()) || !roles.containsKey(equal.right().role())) {
          yield true;
        }
        Optional<List<String>> left = values(equal.left(), roles, index);
        Optional<List<String>> right = values(equal.right(), roles, index);
        yield left.isPresent() && right.isPresent() && left.get().equals(right.get());
      }
      case Constraint.Agree agree ->
          !roles.containsKey(agree.left().role())
              || !roles.containsKey(agree.right().role())
              || values(agree.left(), roles, index).equals(values(agree.right(), roles, index));
      case Constraint.Resolves resolves -> resolves(resolves.path(), roles, index);
      case Constraint.Distinct _ -> true;
    };
  }

  private boolean defined(AttributePath path, Map<String, ProgramObject> roles, Index index) {
    if (path.attributes().isEmpty()) {
      return roles.containsKey(path.role());
    }
    return values(path, roles, index).isPresent();
  }

  private boolean resolves(AttributePath path, Map<String, ProgramObject> roles, Index index) {
    Optional<ProgramObject> holder = holder(path, roles, index);
    if (holder.isEmpty()) {
      return true;
    }
    String name = path.attributes().getLast();
    List<String> values = holder.get().values(name);
    Attribute attribute =
        schema.object(holder.get().ref().type()).flatMap(type -> type.attribute(name)).orElse(null);
    if (attribute == null || !(attribute.type() instanceof Attribute.Type.Reference reference)) {
      return true;
    }
    for (String value : values) {
      if (index.resolve(reference.object(), holder.get().ref().router(), value).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /** Returns the object that holds the last attribute of {@code path}, if the path is defined. */
  private Optional<ProgramObject> holder(
      AttributePath path, Map<String, ProgramObject> roles, Index index) {
    ProgramObject current = roles.get(path.role());
    if (current == null || path.attributes().isEmpty()) {
      return Optional.empty();
    }
    for (String name : path.attributes().subList(0, path.attributes().size() - 1)) {
      Optional<ProgramObject> next = index.follow(current, name, schema);
      if (next.isEmpty()) {
        return Optional.empty();
      }
      current = next.get();
    }
    return Optional.of(current);
  }

  /** Returns the values at {@code path}, or nothing when the path is not defined. */
  Optional<List<String>> values(AttributePath path, Map<String, ProgramObject> roles, Index index) {
    if (path.attributes().isEmpty()) {
      ProgramObject object = roles.get(path.role());
      return object == null ? Optional.empty() : Optional.of(List.of(object.ref().toString()));
    }
    return holder(path, roles, index)
        .map(object -> object.values(path.attributes().getLast()))
        .filter(values -> !values.isEmpty());
  }

  private void markDuplicates(
      AttributePath path,
      Requirement requirement,
      List<Map<String, ProgramObject>> tuples,
      List<List<Requirement>> violations,
      Index index) {
    Map<List<String>, List<Integer>> byValue = new HashMap<>();
    for (int at = 0; at < tuples.size(); at++) {
      int position = at;
      values(path, tuples.get(at), index)
          .ifPresent(value -> byValue.computeIfAbsent(value, v -> new ArrayList<>()).add(position));
    }
    for (List<Integer> sharing : byValue.values()) {
      if (sharing.size() > 1) {
        for (int at : sharing) {
          violations.get(at).add(requirement);
        }
      }
    }
  }

  /** The objects of a program, indexed for joins and reference resolution. */
  static final class Index {
    private final ObjectIndex objects;
    private final Set<ProgramObject.Ref> reads = new HashSet<>();

    Index(ObjectIndex objects) {
      this.objects = objects;
    }

    List<ProgramObject> ofType(String type) {
      return objects.ofType(type);
    }

    List<ProgramObject> withValue(String type, String attribute, List<String> value) {
      return objects.withValue(type, attribute, value);
    }

    /**
     * Resolves reference text to an object of {@code type}: full reference text names any object,
     * and a bare key names one on {@code router}.
     */
    Optional<ProgramObject> resolve(String type, RouterId router, String text) {
      ProgramObject.Ref ref =
          ProgramObject.Ref.isReference(text, type)
              ? ProgramObject.Ref.parse(text)
              : new ProgramObject.Ref(
                  type, router, type.equals(ObjectType.ROUTER) ? List.of() : List.of(text));
      reads.add(ref);
      return Optional.ofNullable(objects.get(ref));
    }

    /** Returns the objects resolved since the last call, and forgets them. */
    Set<ProgramObject.Ref> drainReads() {
      Set<ProgramObject.Ref> drained = Set.copyOf(reads);
      reads.clear();
      return drained;
    }

    /** Follows the reference attribute {@code name} of {@code object}. */
    Optional<ProgramObject> follow(ProgramObject object, String name, RelationSchema schema) {
      List<String> values = object.values(name);
      Attribute attribute =
          schema.object(object.ref().type()).flatMap(type -> type.attribute(name)).orElse(null);
      if (values.isEmpty()
          || attribute == null
          || !(attribute.type() instanceof Attribute.Type.Reference reference)) {
        return Optional.empty();
      }
      return resolve(reference.object(), object.ref().router(), values.getFirst());
    }
  }
}
