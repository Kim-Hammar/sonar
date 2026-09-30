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
    List<String> values = values(attribute);
    return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
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
   * Identifies an object by its type, its router, and its key values. References are compared and
   * hashed by value; the hash is kept, because references key most of the model's maps.
   */
  public static final class Ref {
    private final String type;
    private final RouterId router;
    private final List<String> key;
    private final int hash;

    /**
     * Creates a reference.
     *
     * @param type the object type's name
     * @param router the router whose configuration defines the object
     * @param key the key values in key order; empty for a router
     */
    public Ref(String type, RouterId router, List<String> key) {
      this.type = Objects.requireNonNull(type, "type");
      this.router = Objects.requireNonNull(router, "router");
      this.key = List.copyOf(key);
      this.hash = Objects.hash(type, router, this.key);
    }

    /** Returns the object type's name. */
    public String type() {
      return type;
    }

    /** Returns the router whose configuration defines the object. */
    public RouterId router() {
      return router;
    }

    /** Returns the key values in key order; empty for a router. */
    public List<String> key() {
      return key;
    }

    /** Parses the reference text written by {@link #toString()}. */
    public static Ref parse(String text) {
      String[] parts = text.split(":", 3);
      if (parts.length < 2) {
        throw new IllegalArgumentException("not an object reference: " + text);
      }
      List<String> key = parts.length == 3 ? List.of(parts[2].split(" ")) : List.of();
      return new Ref(parts[0], RouterId.of(parts[1]), key);
    }

    /** Returns whether {@code text} is the reference text of an object of type {@code type}. */
    public static boolean isReference(String text, String type) {
      return text.startsWith(type + ":");
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Ref ref
          && hash == ref.hash
          && type.equals(ref.type)
          && router.equals(ref.router)
          && key.equals(ref.key);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public String toString() {
      return type + ":" + router + (key.isEmpty() ? "" : ":" + String.join(" ", key));
    }
  }
}
