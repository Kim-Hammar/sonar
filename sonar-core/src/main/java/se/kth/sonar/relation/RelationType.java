package se.kth.sonar.relation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A relation between program objects, such as the two ends of a BGP session, with the requirements
 * its instances should meet.
 *
 * <p>The first role ranges over every object of its type for which the join paths are defined; each
 * further role is filled by the object that satisfies the joins, or stays empty. A symmetric
 * relation relates two objects of one type; its instances are unordered pairs and its requirements
 * must hold with the roles either way round.
 *
 * @param name the relation's name
 * @param symmetric whether the relation is symmetric
 * @param roles the roles, driving role first
 * @param joins equalities that determine the further roles
 * @param requirements the requirements
 */
public record RelationType(
    String name,
    boolean symmetric,
    List<Role> roles,
    List<Join> joins,
    List<Requirement> requirements) {

  /** Copies the lists. */
  public RelationType {
    Objects.requireNonNull(name, "name");
    roles = List.copyOf(roles);
    joins = List.copyOf(joins);
    requirements = List.copyOf(requirements);
  }

  /** Returns the role named {@code name}. */
  public Optional<Role> role(String name) {
    return roles.stream().filter(role -> role.name().equals(name)).findFirst();
  }

  /** Returns the driving role. */
  public Role driver() {
    return roles.getFirst();
  }

  /** How many objects fill a role. */
  public enum Cardinality {
    /** The driving role: one instance per object. */
    EACH,
    /** At most one object, determined by the joins. */
    OPTIONAL
  }

  /**
   * A place in a relation, filled by an object of one type.
   *
   * @param name the role's name
   * @param object the object type
   * @param cardinality how many objects fill it
   */
  public record Role(String name, String object, Cardinality cardinality) {}

  /**
   * An equality between two paths that determines a role.
   *
   * @param left one path
   * @param right the other path
   */
  public record Join(AttributePath left, AttributePath right) {}
}
