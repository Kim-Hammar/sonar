package se.kth.sonar.network;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * An IPv4 prefix whose host bits are zero, such as {@code 10.0.0.0/24}.
 *
 * @param network the network address
 * @param length the prefix length, from 0 to 32
 */
public record Prefix(Ipv4 network, int length) implements Comparable<Prefix> {
  private static final Comparator<Prefix> ORDER =
      Comparator.comparing(Prefix::network).thenComparingInt(Prefix::length);

  /** Checks the length and that no host bit is set. */
  public Prefix {
    Objects.requireNonNull(network, "network");
    Ipv4.checkLength(length);
    if (!network.and(Ipv4.mask(length)).equals(network)) {
      throw new IllegalArgumentException("host bits set in " + network + "/" + length);
    }
  }

  /** Parses {@code address/length} notation; host bits must be zero. */
  public static Prefix parse(String text) {
    return tryParse(text)
        .orElseThrow(() -> new IllegalArgumentException("not an IPv4 prefix: " + text));
  }

  /** Parses {@code address/length} notation, or returns nothing if {@code text} is not a prefix. */
  public static Optional<Prefix> tryParse(String text) {
    int slash = text.indexOf('/');
    if (slash < 0 || slash == text.length() - 1 || text.length() - slash > 3) {
      return Optional.empty();
    }
    String digits = text.substring(slash + 1);
    if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')
        || (digits.length() > 1 && digits.charAt(0) == '0')) {
      return Optional.empty();
    }
    int length = Integer.parseInt(digits);
    Optional<Ipv4> address = Ipv4.tryParse(text.substring(0, slash));
    if (address.isEmpty() || length > Integer.SIZE) {
      return Optional.empty();
    }
    Ipv4 network = address.get();
    return network.and(Ipv4.mask(length)).equals(network)
        ? Optional.of(new Prefix(network, length))
        : Optional.empty();
  }

  /** Returns the network mask. */
  public Ipv4 mask() {
    return Ipv4.mask(length);
  }

  /** Returns whether {@code address} lies in this prefix. */
  public boolean contains(Ipv4 address) {
    return address.and(mask()).equals(network);
  }

  /** Returns whether every address of {@code other} lies in this prefix. */
  public boolean contains(Prefix other) {
    return other.length >= length && contains(other.network);
  }

  /** Returns whether the two prefixes share an address. */
  public boolean overlaps(Prefix other) {
    return contains(other) || other.contains(this);
  }

  @Override
  public int compareTo(Prefix other) {
    return ORDER.compare(this, other);
  }

  @Override
  public String toString() {
    return network + "/" + length;
  }
}
