package se.kth.sonar.program;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.text.RouterId;

/**
 * One tuple of objects related by a relation type, with the requirements it violates.
 *
 * @param relation the relation type's name
 * @param roles the object in each filled role, in role order; an empty role has no entry
 * @param violated the requirements the instance violates
 * @param reads the objects whose attributes decided the roles and the violations
 */
public record RelationInstance(
    String relation,
    Map<String, ProgramObject.Ref> roles,
    List<Requirement> violated,
    Set<ProgramObject.Ref> reads) {

  /** Copies the collections. */
  public RelationInstance {
    Objects.requireNonNull(relation, "relation");
    SequencedMap<String, ProgramObject.Ref> copy = new LinkedHashMap<>(roles);
    roles = Collections.unmodifiableSequencedMap(copy);
    violated = List.copyOf(violated);
    reads = Set.copyOf(reads);
  }

  /** Creates an instance that reads only the objects in its roles. */
  public RelationInstance(
      String relation, Map<String, ProgramObject.Ref> roles, List<Requirement> violated) {
    this(relation, roles, violated, Set.copyOf(roles.values()));
  }

  /** Returns whether the instance meets every requirement. */
  public boolean satisfied() {
    return violated.isEmpty();
  }

  /** Returns whether the instance read any object in {@code changed}. */
  public boolean dependsOn(Set<ProgramObject.Ref> changed) {
    for (ProgramObject.Ref ref : reads) {
      if (changed.contains(ref)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the routers of the objects in the instance. */
  public NavigableSet<RouterId> routers() {
    NavigableSet<RouterId> routers = new TreeSet<>();
    roles.values().forEach(ref -> routers.add(ref.router()));
    return Collections.unmodifiableNavigableSet(routers);
  }

  /** Returns an identifier derived from the relation and the objects in its roles. */
  public ContentHash id() {
    ContentHash.Builder digest = ContentHash.builder().add(relation);
    roles.forEach((role, ref) -> digest.add(role).add(ref.toString()));
    return digest.build();
  }
}
