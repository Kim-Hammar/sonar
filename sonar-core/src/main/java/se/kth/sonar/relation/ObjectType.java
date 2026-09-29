package se.kth.sonar.relation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import se.kth.sonar.grammar.Production;

/**
 * A kind of program object, such as an interface or a BGP neighbor.
 *
 * <p>The objects of a type are identified within their router by their key: the values of the key
 * slots in the statements of the source productions. The router type has no key and one object per
 * configuration.
 *
 * @param name the type's name
 * @param key the key slots, in order; empty for the router type
 * @param sources the productions whose statements define objects
 * @param attributes the attributes, including the key components and the implicit {@code router}
 */
public record ObjectType(
    String name,
    List<String> key,
    List<Production> sources,
    SequencedMap<String, Attribute> attributes) {

  /** The name of the router type, which every schema declares. */
  public static final String ROUTER = "router";

  /** Copies the collections. */
  public ObjectType {
    Objects.requireNonNull(name, "name");
    key = List.copyOf(key);
    sources = List.copyOf(sources);
    attributes = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(attributes));
  }

  /** Returns whether this is the router type. */
  public boolean isRouter() {
    return key.isEmpty();
  }

  /** Returns the attribute named {@code name}. */
  public Optional<Attribute> attribute(String name) {
    return Optional.ofNullable(attributes.get(name));
  }
}
