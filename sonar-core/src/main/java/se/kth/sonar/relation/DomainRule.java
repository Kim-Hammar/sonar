package se.kth.sonar.relation;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.grammar.Production;

/**
 * Where the candidate values of one or more slots of a production come from. Two slots, such as an
 * address and a mask, can be filled together from one prefix.
 *
 * @param production the production
 * @param slots the slots filled by the rule
 * @param origin the source of the values
 */
public record DomainRule(Production production, List<String> slots, Origin origin) {
  /** Copies the slots. */
  public DomainRule {
    Objects.requireNonNull(production, "production");
    slots = List.copyOf(slots);
    Objects.requireNonNull(origin, "origin");
  }

  /** Returns whether the rule draws on observed values or invents them. */
  public ValueSupport support() {
    return switch (origin) {
      case Origin.Fresh _ -> ValueSupport.INVENTED;
      case Origin.Verifier _, Origin.Related _ -> ValueSupport.EVIDENCED;
      default -> ValueSupport.OBSERVED;
    };
  }

  /** Which routers a rule draws values from, relative to the router being edited. */
  public enum Scope {
    ROUTER,
    NEIGHBOURS,
    NETWORK
  }

  /** What the verifier reports about violated specifications. */
  public enum Observation {
    DESTINATION,
    SOURCE,
    WAYPOINT,
    ROUTE_HOLDER
  }

  /** The source of a rule's values. */
  public sealed interface Origin {
    /**
     * The values of an attribute of the objects in scope.
     *
     * @param object the object type
     * @param attribute the attribute
     * @param scope the routers to draw from
     */
    record Values(String object, String attribute, Scope scope) implements Origin {}

    /**
     * The keys of the objects of a type in scope.
     *
     * @param object the object type, which has a one-slot key
     * @param scope the routers to draw from
     */
    record Names(String object, Scope scope) implements Origin {}

    /**
     * The value at a path of the relation instance that the edit completes.
     *
     * @param relation the relation
     * @param path the path
     */
    record Related(String relation, AttributePath path) implements Origin {}

    /**
     * Values the verifier reports about violated specifications.
     *
     * @param observation what to take
     */
    record Verifier(Observation observation) implements Origin {}

    /** A value of the slot's sort that is not used in the network. */
    record Fresh() implements Origin {}

    /** Every finite literal declared by the grammar. */
    record Literals() implements Origin {}

    /**
     * The values the slot has in the statements of the same production, nearest router first; the
     * default for a slot that no rule covers.
     *
     * @param scope how far from the edited router to look
     */
    record Observed(Scope scope) implements Origin {}
  }
}
