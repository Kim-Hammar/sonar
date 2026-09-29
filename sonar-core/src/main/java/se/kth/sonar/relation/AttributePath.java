package se.kth.sonar.relation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A path from a relation role through object attributes, written {@code role.attribute.attribute}.
 * Every attribute but the last must be a reference.
 *
 * @param role the role the path starts at
 * @param attributes the attributes to follow, possibly none
 */
public record AttributePath(String role, List<String> attributes) {
  private static final Pattern DOT = Pattern.compile("\\.");

  /** Copies the attributes. */
  public AttributePath {
    Objects.requireNonNull(role, "role");
    attributes = List.copyOf(attributes);
  }

  /** Parses {@code role.attribute...}. */
  public static AttributePath parse(String text) {
    List<String> parts = Arrays.asList(DOT.split(text, -1));
    if (parts.stream().anyMatch(String::isEmpty)) {
      throw new IllegalArgumentException("malformed path '" + text + "'");
    }
    return new AttributePath(parts.getFirst(), parts.subList(1, parts.size()));
  }

  @Override
  public String toString() {
    return attributes.isEmpty() ? role : role + "." + String.join(".", attributes);
  }
}
