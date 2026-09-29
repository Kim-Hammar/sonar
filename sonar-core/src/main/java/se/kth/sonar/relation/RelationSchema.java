package se.kth.sonar.relation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Production;

/**
 * The semantics that repair generation needs on top of a grammar: object types, the relations
 * between objects, and the domains candidate values come from. Schemas are created by {@link
 * RelationDsl}, which validates them against their grammar.
 */
public final class RelationSchema {
  private final String name;
  private final Grammar grammar;
  private final Map<String, ObjectType> objects;
  private final Map<String, RelationType> relations;
  private final List<DomainRule> domains;

  RelationSchema(
      String name,
      Grammar grammar,
      Collection<ObjectType> objects,
      Collection<RelationType> relations,
      List<DomainRule> domains) {
    this.name = Objects.requireNonNull(name, "name");
    this.grammar = Objects.requireNonNull(grammar, "grammar");
    Map<String, ObjectType> objectMap = new LinkedHashMap<>();
    objects.forEach(object -> objectMap.put(object.name(), object));
    this.objects = Collections.unmodifiableMap(objectMap);
    Map<String, RelationType> relationMap = new LinkedHashMap<>();
    relations.forEach(relation -> relationMap.put(relation.name(), relation));
    this.relations = Collections.unmodifiableMap(relationMap);
    this.domains = List.copyOf(domains);
  }

  /** Returns the schema's name. */
  public String name() {
    return name;
  }

  /** Returns the grammar the schema is written against. */
  public Grammar grammar() {
    return grammar;
  }

  /** Returns the object types in declaration order. */
  public Collection<ObjectType> objects() {
    return objects.values();
  }

  /** Returns the object type named {@code name}. */
  public Optional<ObjectType> object(String name) {
    return Optional.ofNullable(objects.get(name));
  }

  /** Returns the router type. */
  public ObjectType router() {
    return objects.get(ObjectType.ROUTER);
  }

  /** Returns the relations in declaration order. */
  public Collection<RelationType> relations() {
    return relations.values();
  }

  /** Returns the relation named {@code name}. */
  public Optional<RelationType> relation(String name) {
    return Optional.ofNullable(relations.get(name));
  }

  /** Returns the domain rules in declaration order. */
  public List<DomainRule> domains() {
    return domains;
  }

  /** Returns the domain rules for the slots of {@code production}. */
  public List<DomainRule> domains(Production production) {
    return domains.stream().filter(rule -> rule.production().equals(production)).toList();
  }

  /** Returns the names of the functions that compute derived attributes. */
  public Set<String> derivedFunctions() {
    Set<String> functions = new TreeSet<>();
    for (ObjectType object : objects.values()) {
      for (Attribute attribute : object.attributes().values()) {
        if (attribute.source() instanceof Attribute.Source.Derived derived) {
          functions.add(derived.function());
        }
      }
    }
    return Collections.unmodifiableSet(functions);
  }

  @Override
  public String toString() {
    return "RelationSchema["
        + name
        + ", "
        + objects.size()
        + " objects, "
        + relations.size()
        + " relations]";
  }
}
