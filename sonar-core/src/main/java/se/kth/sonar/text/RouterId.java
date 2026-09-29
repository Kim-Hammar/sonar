package se.kth.sonar.text;

import java.util.Locale;
import java.util.Objects;

/**
 * Identifies a router by the lower-case stem of its configuration file name, which is also how
 * specifications name routers.
 *
 * @param name the lower-case name
 */
public record RouterId(String name) implements Comparable<RouterId> {
  /** Checks that the name is non-empty, lower-case, and free of whitespace. */
  public RouterId {
    Objects.requireNonNull(name, "name");
    if (name.isEmpty()
        || !name.equals(name.toLowerCase(Locale.ROOT))
        || name.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("invalid router name: '" + name + "'");
    }
  }

  /** Returns the identifier for a router or host name, ignoring case. */
  public static RouterId of(String name) {
    return new RouterId(name.toLowerCase(Locale.ROOT));
  }

  /** Returns the identifier for a configuration file name such as {@code R1.cfg}. */
  public static RouterId fromFileName(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return of(dot > 0 ? fileName.substring(0, dot) : fileName);
  }

  @Override
  public int compareTo(RouterId other) {
    return name.compareTo(other.name);
  }

  @Override
  public String toString() {
    return name;
  }
}
