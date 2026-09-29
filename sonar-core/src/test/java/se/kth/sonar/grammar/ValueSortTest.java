package se.kth.sonar.grammar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValueSortTest {
  private static final Lexeme ADDRESS = new Lexeme.Address();
  private static final Lexeme MASK = new Lexeme.Mask();
  private static final Lexeme WILDCARD = new Lexeme.Wildcard();
  private static final Lexeme PREFIX = new Lexeme.AddressPrefix();
  private static final Lexeme NET = new Lexeme.IsoNet();
  private static final Lexeme PORT = new Lexeme.IntegerRange(1, 65535);
  private static final Lexeme INTERFACE =
      new Lexeme.InterfaceName(List.of("Loopback", "GigabitEthernet"));
  private static final Lexeme NAME = new Lexeme.Name();
  private static final Lexeme ACTION = new Lexeme.Enumeration(List.of("permit", "deny"));
  private static final Lexeme COMMUNITY = new Lexeme.Community();
  private static final Lexeme TEXT = new Lexeme.Text();

  static Stream<Arguments> tokens() {
    return Stream.of(
        Arguments.of(
            ADDRESS,
            List.of("10.0.0.1", "0.0.0.0", "255.255.255.255"),
            List.of("10.0.0", "10.0.0.256", "010.0.0.1", "a.b.c.d")),
        Arguments.of(
            MASK,
            List.of("255.255.255.0", "0.0.0.0", "255.255.255.255"),
            List.of("255.0.255.0", "0.0.0.255")),
        Arguments.of(
            WILDCARD,
            List.of("0.0.0.255", "0.0.0.1", "0.0.0.0"),
            List.of("255.255.255.0", "0.0.255.0")),
        Arguments.of(
            PREFIX,
            List.of("10.0.0.0/8", "0.0.0.0/0", "10.180.0.1/32"),
            List.of("10.0.0.1/8", "10.0.0.0/33", "10.0.0.0/08", "10.0.0.0")),
        Arguments.of(
            NET,
            List.of("49.0001.0000.0000.0001.00", "31.ee01.0000.0ab4.0001.00"),
            List.of("49.0001.00", "10.0.0.1")),
        Arguments.of(PORT, List.of("1", "65535"), List.of("0", "65536", "01", "-1", "1a", "")),
        Arguments.of(
            INTERFACE,
            List.of("Loopback0", "GigabitEthernet0/0/1", "GigabitEthernet0/1.100"),
            List.of("Loopback", "FastEthernet0/0", "Loopbackx")),
        Arguments.of(
            NAME,
            List.of("RM_POLICY_IN_1", "0_as65002_Linz", "RR"),
            List.of("65002", "10.0.0.1", "10.0.0.0/8")),
        Arguments.of(ACTION, List.of("permit", "deny"), List.of("allow", "Permit")),
        Arguments.of(
            COMMUNITY,
            List.of("65000:100", "no-export", "0:0"),
            List.of("65536:1", "65000", "no_export")),
        Arguments.of(TEXT, List.of("(97)", "to"), List.of("")));
  }

  @ParameterizedTest
  @MethodSource("tokens")
  void lexemesAcceptExactlyTheirTokens(
      Lexeme lexeme, List<String> accepted, List<String> rejected) {
    assertThat(accepted).allMatch(lexeme::accepts);
    assertThat(rejected).noneMatch(lexeme::accepts);
  }

  static Stream<Arguments> pairs() {
    return Stream.of(
        Arguments.of(ADDRESS, MASK, true),
        Arguments.of(MASK, WILDCARD, true),
        Arguments.of(ADDRESS, PREFIX, false),
        Arguments.of(ADDRESS, PORT, false),
        Arguments.of(ADDRESS, NAME, false),
        Arguments.of(NAME, INTERFACE, true),
        Arguments.of(NAME, NET, true),
        Arguments.of(NET, INTERFACE, false),
        Arguments.of(NET, ADDRESS, false),
        Arguments.of(INTERFACE, new Lexeme.InterfaceName(List.of("Serial")), false),
        Arguments.of(INTERFACE, new Lexeme.InterfaceName(List.of("Serial", "Loopback")), true),
        Arguments.of(new Lexeme.IntegerRange(1, 10), new Lexeme.IntegerRange(10, 20), true),
        Arguments.of(new Lexeme.IntegerRange(1, 9), new Lexeme.IntegerRange(10, 20), false),
        Arguments.of(ACTION, NAME, true),
        Arguments.of(ACTION, PORT, false),
        Arguments.of(new Lexeme.Enumeration(List.of("10")), PORT, true),
        Arguments.of(COMMUNITY, NAME, true),
        Arguments.of(COMMUNITY, PORT, false),
        Arguments.of(COMMUNITY, COMMUNITY, true),
        Arguments.of(TEXT, PORT, true));
  }

  @ParameterizedTest
  @MethodSource("pairs")
  void overlapIsExactAndSymmetric(Lexeme a, Lexeme b, boolean expected) {
    assertThat(Lexeme.overlap(a, b)).isEqualTo(expected);
    assertThat(Lexeme.overlap(b, a)).isEqualTo(expected);
  }

  @Test
  void sortsDelegateToTheirLexeme() {
    ValueSort asn = new ValueSort("asn", new Lexeme.IntegerRange(1, 4_294_967_295L));
    ValueSort cost = new ValueSort("cost", new Lexeme.IntegerRange(1, 65535));
    assertThat(asn.accepts("4294967295")).isTrue();
    assertThat(asn.overlaps(cost)).isTrue();
    assertThat(asn.lexeme().describe()).isEqualTo("integer 1..4294967295");
  }
}
