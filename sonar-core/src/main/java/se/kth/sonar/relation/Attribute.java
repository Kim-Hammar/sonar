package se.kth.sonar.relation;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.ValueSort;

/**
 * A named property of a program object.
 *
 * @param name the attribute's name, unique within its object type
 * @param type the type of the attribute's values
 * @param many whether an object may have several values
 * @param source where the values come from
 */
public record Attribute(String name, Type type, boolean many, Source source) {
  /** Checks the components. */
  public Attribute {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(source, "source");
  }

  /** The type of an attribute's values. */
  public sealed interface Type {
    /**
     * Tokens of a sort.
     *
     * @param sort the sort
     */
    record Value(ValueSort sort) implements Type {}

    /** Whether a statement is present. */
    record Flag() implements Type {}

    /**
     * The key of another object on the same router.
     *
     * @param object the referenced object type
     */
    record Reference(String object) implements Type {}
  }

  /** Where an attribute's values come from. */
  public sealed interface Source {
    /** A component of the object's key. */
    record KeyComponent() implements Source {}

    /** The router whose configuration defines the object. */
    record Owner() implements Source {}

    /**
     * Slot values of the statements bound to the object.
     *
     * @param slots the slots to read, in their productions
     * @param by the slots that name the object in statements outside its block; empty when the
     *     statements are inside the block or name it with slots called like its key
     * @param where conditions a statement must meet to count
     */
    record Slots(List<SlotRef> slots, List<String> by, List<Condition> where) implements Source {
      /** Copies the lists. */
      public Slots {
        slots = List.copyOf(slots);
        by = List.copyOf(by);
        where = List.copyOf(where);
      }
    }

    /**
     * The presence of a bound statement of one of {@code productions}.
     *
     * @param productions the productions
     * @param by as for {@link Slots}
     * @param where conditions a statement must meet to count
     */
    record Presence(List<Production> productions, List<String> by, List<Condition> where)
        implements Source {
      /** Copies the lists. */
      public Presence {
        productions = List.copyOf(productions);
        by = List.copyOf(by);
        where = List.copyOf(where);
      }
    }

    /**
     * A value that needs the semantics of the configuration language, computed by the program model
     * factory.
     *
     * @param function the name of the function that computes the value
     */
    record Derived(String function) implements Source {}
  }

  /**
   * One slot of one production.
   *
   * @param production the production
   * @param slot the slot's name
   */
  public record SlotRef(Production production, String slot) {
    @Override
    public String toString() {
      return production.id() + "." + slot;
    }
  }

  /**
   * A requirement that a slot of a statement holds a literal.
   *
   * @param slot the slot's name
   * @param literal the required token
   */
  public record Condition(String slot, String literal) {}
}
