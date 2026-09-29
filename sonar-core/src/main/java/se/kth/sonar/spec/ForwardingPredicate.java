package se.kth.sonar.spec;

import java.util.Objects;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.text.RouterId;

/** A forwarding property of a network's data plane, as named by one row of a specification file. */
public sealed interface ForwardingPredicate {
  /** Returns the router the traffic starts at; specification files call it the node. */
  RouterId source();

  /** Returns the destination prefix. */
  Prefix destination();

  /** Returns the predicate type as written in specification files. */
  String type();

  /** Returns a canonical text form that identifies the predicate. */
  String key();

  /**
   * Traffic from {@code source} reaches {@code destination}.
   *
   * @param source the source router
   * @param destination the destination prefix
   */
  record Reachability(RouterId source, Prefix destination) implements ForwardingPredicate {
    /** Checks the components. */
    public Reachability {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(destination, "destination");
    }

    @Override
    public String type() {
      return "reachability";
    }

    @Override
    public String key() {
      return type() + "|" + source + "|" + destination;
    }
  }

  /**
   * Traffic from {@code source} does not reach {@code destination}.
   *
   * @param source the source router
   * @param destination the destination prefix
   */
  record Isolation(RouterId source, Prefix destination) implements ForwardingPredicate {
    /** Checks the components. */
    public Isolation {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(destination, "destination");
    }

    @Override
    public String type() {
      return "isolation";
    }

    @Override
    public String key() {
      return type() + "|" + source + "|" + destination;
    }
  }

  /**
   * Traffic from {@code source} to {@code destination} passes through {@code waypoint}.
   *
   * @param source the source router
   * @param destination the destination prefix
   * @param waypoint the router the traffic must traverse
   */
  record Waypointing(RouterId source, Prefix destination, RouterId waypoint)
      implements ForwardingPredicate {
    /** Checks the components. */
    public Waypointing {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(destination, "destination");
      Objects.requireNonNull(waypoint, "waypoint");
    }

    @Override
    public String type() {
      return "waypointing";
    }

    @Override
    public String key() {
      return type() + "|" + source + "|" + destination + "|" + waypoint;
    }
  }

  /**
   * Traffic from {@code source} to {@code destination} is spread over {@code routes} paths.
   *
   * @param source the source router
   * @param destination the destination prefix
   * @param routes the number of equal-cost paths
   */
  record LoadBalancing(RouterId source, Prefix destination, int routes)
      implements ForwardingPredicate {
    /** Checks the components. */
    public LoadBalancing {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(destination, "destination");
      if (routes < 1) {
        throw new IllegalArgumentException("routes must be at least 1: " + routes);
      }
    }

    @Override
    public String type() {
      return "load_balancing";
    }

    @Override
    public String key() {
      return type() + "|" + source + "|" + destination + "|" + routes;
    }
  }
}
