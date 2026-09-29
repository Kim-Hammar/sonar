package se.kth.sonar.program;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

/**
 * A typed object of a program, such as an interface or a BGP neighbor, with its attribute values
 * and the statements that define it.
 *
 * @param ref the object's identity
 * @param attributes the values of every attribute that has values, in schema order
 * @param statements the lines of the statements bound to the object
 */
public record ProgramObject(
    Ref ref, Map<String, List<String>> attributes, List<SourceSpan> statements) {

  /** Copies the collections. */
  public ProgramObject {
    Objects.requireNonNull(ref, "ref");
    SequencedMap<String, List<String>> copy = new LinkedHashMap<>();
    attributes.forEach((name, values) -> copy.put(name, List.copyOf(values)));
    attributes = Collections.unmodifiableSequencedMap(copy);
    statements = List.copyOf(statements);
  }

  /** Returns the first value of {@code attribute}, if it has one. */
  public Optional<String> value(String attribute) {
    return values(attribute).stream().findFirst();
  }

  /** Returns the values of {@code attribute}, which are empty if it has none. */
  public List<String> values(String attribute) {
    return attributes.getOrDefault(attribute, List.of());
  }

  /** Returns whether {@code attribute} has a value; a flag has one when it is set. */
  public boolean has(String attribute) {
    return !values(attribute).isEmpty();
  }

  /**
   * Identifies an object by its type, its router, and its key values.
   *
   * @param type the object type's name
   * @param router the router whose configuration defines the object
   * @param key the key values in key order; empty for a router
   */
  public record Ref(String type, RouterId router, List<String> key) {
    /** Copies the key. */
    public Ref {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(router, "router");
      key = List.copyOf(key);
    }

    @Override
    public String toString() {
      return type + ":" + router + (key.isEmpty() ? "" : ":" + String.join(" ", key));
    }
  }
}
