package se.kth.sonar.grammar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;

class GrammarParseTest {
  private static final Grammar GRAMMAR = Dialect.load("cisco-ios").grammar();
  private static final RouterId R1 = RouterId.of("r1");

  private static final String CONFIG =
      """
      hostname r1
      !
      interface Loopback0
       ip address 10.0.0.1 255.255.255.255
       ip ospf 1 area 0
      !
      router ospf 1
       router-id 10.0.0.1
      network 10.0.0.0 0.0.0.255 area 0
      !
      router bgp 65001
       neighbor 10.0.0.2 remote-as 65001
       address-family ipv4
        neighbor 10.0.0.2 activate
       exit-address-family
       bgp log-neighbor-changes
      !
      route-map OUT permit 10
       ! curated prefixes
       match ip address prefix-list PL
       set weight 100
      ip route 10.9.0.0 255.255.0.0 Loopback0
      """;

  @Test
  void placesLinesInTheDeviceModesTheyBelongTo() {
    List<Statement> statements = GRAMMAR.parse(R1, ConfigText.of(CONFIG));
    assertThat(statements)
        .extracting(s -> s.span().first())
        .containsExactly(1, 3, 4, 5, 7, 8, 9, 11, 12, 13, 14, 15, 16, 18, 20, 21, 22);
    assertThat(context(statements, 4)).isEqualTo("interface");
    assertThat(statements.get(2).opener()).isEqualTo(3);
    assertThat(statements.get(2).indentation()).isEqualTo(" ");
    assertThat(context(statements, 9)).isEqualTo("router-ospf");
    assertThat(production(statements, 9)).isEqualTo("ospf-network");
    assertThat(context(statements, 13)).isEqualTo("router-bgp");
    assertThat(context(statements, 14)).isEqualTo("address-family");
    assertThat(context(statements, 15)).isEqualTo("address-family");
    assertThat(context(statements, 16)).isEqualTo("router-bgp");
    assertThat(context(statements, 18)).isEqualTo("root");
    assertThat(context(statements, 20)).isEqualTo("route-map");
    assertThat(statements.get(14).matched()).isTrue();
    assertThat(context(statements, 21)).isEqualTo("route-map");
    assertThat(statements.get(15).matched()).isFalse();
    assertThat(statements.get(15).text()).isEqualTo("set weight 100");
    assertThat(context(statements, 22)).isEqualTo("root");
    assertThat(production(statements, 22)).isEqualTo("static-route-interface");
  }

  @Test
  void anEmptyTextHasNoStatements() {
    assertThat(GRAMMAR.parse(R1, ConfigText.of(""))).isEmpty();
    assertThat(GRAMMAR.parse(R1, ConfigText.of("!\n\n! only comments\n"))).isEmpty();
  }

  private static String context(List<Statement> statements, int line) {
    return statements.stream()
        .filter(statement -> statement.span().first() == line)
        .findFirst()
        .orElseThrow()
        .context()
        .name();
  }

  private static String production(List<Statement> statements, int line) {
    return statements.stream()
        .filter(statement -> statement.span().first() == line)
        .findFirst()
        .orElseThrow()
        .match()
        .orElseThrow()
        .production()
        .id();
  }
}
