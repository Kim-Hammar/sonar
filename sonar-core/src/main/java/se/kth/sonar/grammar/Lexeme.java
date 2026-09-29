package se.kth.sonar.grammar;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import se.kth.sonar.network.Ipv4;
import se.kth.sonar.network.Prefix;

/**
 * A set of tokens that the grammar language knows how to recognise. A dialect names and
 * parameterises lexemes in its sort declarations.
 *
 * <p>Tokens of different lexemes are told apart by their shape: addresses, masks and wildcards are
 * dotted quads, prefixes add a slash, ISO network titles have at least five dotted groups, integers
 * are plain digits, community values are pairs of numbers joined by a colon, and interface names
 * and names contain a letter. This makes {@link #overlap} exact.
 */
public sealed interface Lexeme {
  /** Returns whether {@code token} belongs to the lexeme. */
  boolean accepts(String token);

  /** Returns how the lexeme is written in a grammar source. */
  String describe();

  /** Returns the literal tokens the lexeme accepts in addition to its pattern. */
  default List<String> literals() {
    return List.of();
  }

  /** Returns whether some token belongs to both lexemes. */
  static boolean overlap(Lexeme a, Lexeme b) {
    if (a instanceof Text || b instanceof Text) {
      return true;
    }
    if (a.literals().stream().anyMatch(b::accepts) || b.literals().stream().anyMatch(a::accepts)) {
      return true;
    }
    return patternsOverlap(a, b) || patternsOverlap(b, a);
  }

  private static boolean patternsOverlap(Lexeme a, Lexeme b) {
    return switch (a) {
      case Address _, Mask _, Wildcard _ ->
          b instanceof Address || b instanceof Mask || b instanceof Wildcard;
      case AddressPrefix _ -> b instanceof AddressPrefix;
      case IsoNet _ -> b instanceof IsoNet || b instanceof Name;
      case IntegerRange range ->
          b instanceof IntegerRange other
              && range.min() <= other.max()
              && other.min() <= range.max();
      case InterfaceName names ->
          b instanceof Name
              || (b instanceof InterfaceName other
                  && !Collections.disjoint(names.stems(), other.stems()));
      case Name _ -> b instanceof Name;
      case Community _ -> b instanceof Community;
      case Enumeration _, Text _ -> false;
    };
  }

  /** A dotted-quad IPv4 address, written {@code ipv4}. */
  record Address() implements Lexeme {
    @Override
    public boolean accepts(String token) {
      return Ipv4.tryParse(token).isPresent();
    }

    @Override
    public String describe() {
      return "ipv4";
    }
  }

  /** A network mask with contiguous leading ones, written {@code mask}. */
  record Mask() implements Lexeme {
    @Override
    public boolean accepts(String token) {
      return Ipv4.tryParse(token).map(address -> address.maskLength().isPresent()).orElse(false);
    }

    @Override
    public String describe() {
      return "mask";
    }
  }

  /** A wildcard mask with contiguous trailing ones, written {@code wildcard}. */
  record Wildcard() implements Lexeme {
    @Override
    public boolean accepts(String token) {
      return Ipv4.tryParse(token)
          .map(address -> address.wildcardLength().isPresent())
          .orElse(false);
    }

    @Override
    public String describe() {
      return "wildcard";
    }
  }

  /** An IPv4 prefix whose host bits are zero, written {@code prefix}. */
  record AddressPrefix() implements Lexeme {
    @Override
    public boolean accepts(String token) {
      return Prefix.tryParse(token).isPresent();
    }

    @Override
    public String describe() {
      return "prefix";
    }
  }

  /**
   * An IS-IS network entity title such as {@code 49.0001.0000.0000.0001.00}, written {@code
   * iso-net}.
   */
  record IsoNet() implements Lexeme {
    private static final Pattern NET =
        Pattern.compile("\\p{XDigit}{2}(\\.\\p{XDigit}{4}){3,}\\.\\p{XDigit}{2}");

    @Override
    public boolean accepts(String token) {
      return NET.matcher(token).matches();
    }

    @Override
    public String describe() {
      return "iso-net";
    }
  }

  /**
   * A decimal integer without sign or leading zeros, written {@code integer min..max}.
   *
   * @param min the smallest accepted value, at least 0
   * @param max the largest accepted value
   */
  record IntegerRange(long min, long max) implements Lexeme {
    private static final int MAX_DIGITS = 18;

    /** Checks that the range is non-empty and not negative. */
    public IntegerRange {
      if (min < 0 || max < min) {
        throw new IllegalArgumentException("invalid integer range " + min + ".." + max);
      }
    }

    @Override
    public boolean accepts(String token) {
      return value(token).filter(value -> value >= min && value <= max).isPresent();
    }

    @Override
    public String describe() {
      return "integer " + min + ".." + max;
    }

    /** Returns the value of a canonical decimal token. */
    static Optional<Long> value(String token) {
      if (token.isEmpty()
          || token.length() > MAX_DIGITS
          || (token.length() > 1 && token.charAt(0) == '0')
          || !token.chars().allMatch(c -> c >= '0' && c <= '9')) {
        return Optional.empty();
      }
      return Optional.of(Long.parseLong(token));
    }
  }

  /**
   * An interface name: one of the stems followed by a slot path such as {@code 0/1.100}, written
   * {@code interface Loopback GigabitEthernet}.
   *
   * @param stems the interface type names, made of letters and hyphens
   */
  record InterfaceName(List<String> stems) implements Lexeme {
    private static final Pattern STEM = Pattern.compile("\\p{Alpha}[\\p{Alpha}-]*");
    private static final Pattern PATH = Pattern.compile("\\d+(/\\d+)*(\\.\\d+)?");

    /** Checks that there is at least one stem and that every stem is well formed. */
    public InterfaceName {
      stems = List.copyOf(stems);
      if (stems.isEmpty() || Set.copyOf(stems).size() != stems.size()) {
        throw new IllegalArgumentException("interface stems must be distinct and non-empty");
      }
      for (String stem : stems) {
        if (!STEM.matcher(stem).matches()) {
          throw new IllegalArgumentException("invalid interface stem: " + stem);
        }
      }
    }

    @Override
    public boolean accepts(String token) {
      for (String stem : stems) {
        if (token.startsWith(stem)
            && PATH.matcher(token).region(stem.length(), token.length()).matches()) {
          return true;
        }
      }
      return false;
    }

    @Override
    public String describe() {
      return "interface " + String.join(" ", stems);
    }
  }

  /**
   * A name, such as a route-map or host name: any token with a letter or underscore, written {@code
   * name}.
   */
  record Name() implements Lexeme {
    @Override
    public boolean accepts(String token) {
      return token.chars().anyMatch(c -> c == '_' || (c < 128 && Character.isLetter(c)));
    }

    @Override
    public String describe() {
      return "name";
    }
  }

  /**
   * A fixed set of keywords, written {@code enum permit deny}.
   *
   * @param literals the accepted tokens, distinct
   */
  record Enumeration(List<String> literals) implements Lexeme {
    /** Checks that the literals are distinct and non-empty. */
    public Enumeration {
      literals = List.copyOf(literals);
      if (literals.isEmpty() || Set.copyOf(literals).size() != literals.size()) {
        throw new IllegalArgumentException("enumeration literals must be distinct and non-empty");
      }
    }

    @Override
    public boolean accepts(String token) {
      return literals.contains(token);
    }

    @Override
    public String describe() {
      return "enum " + String.join(" ", literals);
    }
  }

  /**
   * A BGP community value: two numbers up to 65535 joined by a colon, or a well-known community
   * name, written {@code community}.
   */
  record Community() implements Lexeme {
    private static final List<String> WELL_KNOWN =
        List.of("internet", "local-AS", "no-advertise", "no-export", "gshut");
    private static final long MAX_PART = 65_535;

    @Override
    public boolean accepts(String token) {
      if (WELL_KNOWN.contains(token)) {
        return true;
      }
      int colon = token.indexOf(':');
      return colon > 0 && part(token.substring(0, colon)) && part(token.substring(colon + 1));
    }

    @Override
    public List<String> literals() {
      return WELL_KNOWN;
    }

    @Override
    public String describe() {
      return "community";
    }

    private static boolean part(String digits) {
      return IntegerRange.value(digits).filter(value -> value <= MAX_PART).isPresent();
    }
  }

  /** Any token, for free text such as descriptions, written {@code text}. */
  record Text() implements Lexeme {
    @Override
    public boolean accepts(String token) {
      return !Objects.requireNonNull(token, "token").isEmpty();
    }

    @Override
    public String describe() {
      return "text";
    }
  }
}
