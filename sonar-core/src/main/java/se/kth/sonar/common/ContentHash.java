package se.kth.sonar.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A SHA-256 digest that identifies content by value.
 *
 * @param hex the digest as 64 lower-case hexadecimal characters
 */
public record ContentHash(String hex) implements Comparable<ContentHash> {
  private static final HexFormat HEX = HexFormat.of();
  private static final int HEX_LENGTH = 64;

  /** Checks that {@code hex} is a lower-case SHA-256 digest. */
  public ContentHash {
    Objects.requireNonNull(hex, "hex");
    if (hex.length() != HEX_LENGTH || !hex.chars().allMatch(ContentHash::isLowerHexDigit)) {
      throw new IllegalArgumentException("not a SHA-256 digest: " + hex);
    }
  }

  /** Returns the digest of the UTF-8 encoding of {@code text}. */
  public static ContentHash of(String text) {
    return of(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Returns the digest of {@code bytes}. */
  public static ContentHash of(byte[] bytes) {
    return new ContentHash(HEX.formatHex(sha256().digest(bytes)));
  }

  /** Starts a digest over a sequence of fields. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the first twelve hexadecimal characters, for display. */
  public String abbreviated() {
    return hex.substring(0, 12);
  }

  /** Returns the first eight bytes of the digest as a number, for seeding random generators. */
  public long asLong() {
    return HexFormat.fromHexDigitsToLong(hex, 0, 16);
  }

  @Override
  public int compareTo(ContentHash other) {
    return hex.compareTo(other.hex);
  }

  @Override
  public String toString() {
    return hex;
  }

  private static boolean isLowerHexDigit(int c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Digests a sequence of typed fields. Every field is tagged with its type and prefixed with its
   * length, so two different sequences never share an encoding.
   */
  public static final class Builder {
    private static final byte TEXT = 1;
    private static final byte NUMBER = 2;
    private final MessageDigest digest = sha256();

    private Builder() {}

    /** Appends a text field. */
    public Builder add(String field) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      header(TEXT, bytes.length);
      digest.update(bytes);
      return this;
    }

    /** Appends a number field. */
    public Builder add(long field) {
      header(NUMBER, Long.BYTES);
      digest.update(ByteBuffer.allocate(Long.BYTES).putLong(field).array());
      return this;
    }

    /** Appends another digest. */
    public Builder add(ContentHash field) {
      return add(field.hex());
    }

    /** Returns the digest of the fields appended so far and resets the builder. */
    public ContentHash build() {
      return new ContentHash(HEX.formatHex(digest.digest()));
    }

    private void header(byte tag, int length) {
      digest.update(tag);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }
  }
}
