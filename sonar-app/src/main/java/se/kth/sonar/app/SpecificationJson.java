package se.kth.sonar.app;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;

/** Writes specifications as the JSON rows that run logs and the web API share. */
public final class SpecificationJson {
  private SpecificationJson() {}

  /** Returns the row of {@code specification}. */
  public static ObjectNode row(Specification specification) {
    ForwardingPredicate predicate = specification.predicate();
    ObjectNode row = Json.mapper().createObjectNode();
    row.put("key", predicate.key());
    row.put("type", predicate.type());
    row.put("source", predicate.source().name());
    row.put("destination", predicate.destination().toString());
    switch (predicate) {
      case ForwardingPredicate.Waypointing waypointing ->
          row.put("waypoint", waypointing.waypoint().name());
      case ForwardingPredicate.LoadBalancing balancing -> row.put("routes", balancing.routes());
      case ForwardingPredicate.Reachability _, ForwardingPredicate.Isolation _ -> {}
    }
    row.put("status", specification.status().label());
    row.put("initial", specification.status().initial().name().toLowerCase(Locale.ROOT));
    row.put("expectPresent", specification.status().expectPresent());
    return row;
  }
}
