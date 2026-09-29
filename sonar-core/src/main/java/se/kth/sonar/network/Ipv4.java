package se.kth.sonar.network;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * An IPv4 address.
 *
 * @param bits the address as a 32-bit value
 */
public record Ipv4(int bits) implements Comparable<Ipv4> {
  private static final int OCTETS = 4;
  private static final int MAX_OCTET = 255;

  /**
   * Parses canonical dotted-quad notation, such as {@code 10.0.0.1}; leading zeros are rejected.
   */
  public static Ipv4 parse(String text) {
    return tryParse(text)
        .orElseThrow(() -> new IllegalArgumentException("not an IPv4 address: " + text));
  }

  /**
   * Parses canonical dotted-quad notation, or returns nothing if {@code text} is not an address.
   */
  public static Optional<Ipv4> tryParse(String text) {
    int bits = 0;
    int octets = 0;
    int start = 0;
    while (octets < OCTETS) {
      int end = text.indexOf('.', start);
      if (end < 0) {
        end = text.length();
      }
      OptionalInt octet = octet(text, start, end);
      if (octet.isEmpty() || (octets < OCTETS - 1) == (end == text.length())) {
        return Optional.empty();
      }
      bits = (bits << 8) | octet.getAsInt();
      octets++;
      start = end + 1;
    }
    return Optional.of(new Ipv4(bits));
  }

  /** Returns the network mask with {@code length} leading one bits. */
  public static Ipv4 mask(int length) {
    checkLength(length);
    return new Ipv4(length == 0 ? 0 : -1 << (Integer.SIZE - length));
  }

  /** Returns the number of leading one bits if this address is a network mask. */
  public OptionalInt maskLength() {
    int ones = Integer.numberOfLeadingZeros(~bits);
    return bits == (ones == 0 ? 0 : -1 << (Integer.SIZE - ones))
        ? OptionalInt.of(ones)
        : OptionalInt.empty();
  }

  /** Returns the number of leading zero bits if this address is a wildcard (inverted) mask. */
  public OptionalInt wildcardLength() {
    return new Ipv4(~bits).maskLength();
  }

  /** Returns the bitwise conjunction with {@code mask}. */
  public Ipv4 and(Ipv4 mask) {
    return new Ipv4(bits & mask.bits);
  }

  @Override
  public int compareTo(Ipv4 other) {
    return Integer.compareUnsigned(bits, other.bits);
  }

  @Override
  public String toString() {
    return ((bits >>> 24) & MAX_OCTET)
        + "."
        + ((bits >>> 16) & MAX_OCTET)
        + "."
        + ((bits >>> 8) & MAX_OCTET)
        + "."
        + (bits & MAX_OCTET);
  }

  static void checkLength(int length) {
    if (length < 0 || length > Integer.SIZE) {
      throw new IllegalArgumentException("prefix length must lie in [0, 32]: " + length);
    }
  }

  private static OptionalInt octet(String text, int start, int end) {
    int length = end - start;
    if (length < 1 || length > 3 || (length > 1 && text.charAt(start) == '0')) {
      return OptionalInt.empty();
    }
    int value = 0;
    for (int index = start; index < end; index++) {
      char c = text.charAt(index);
      if (c < '0' || c > '9') {
        return OptionalInt.empty();
      }
      value = value * 10 + (c - '0');
    }
    return value <= MAX_OCTET ? OptionalInt.of(value) : OptionalInt.empty();
  }
}
