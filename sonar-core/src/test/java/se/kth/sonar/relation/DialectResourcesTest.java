package se.kth.sonar.relation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Production;

class DialectResourcesTest {
  private static final Dialect CISCO = Dialect.load("cisco-ios");

  @Test
  void shippedDialectParsesAndValidates() {
    assertThat(CISCO.grammar().name()).isEqualTo("cisco-ios");
    assertThat(CISCO.schema().name()).isEqualTo("cisco-ios");
    assertThat(CISCO.grammar().productions()).hasSizeGreaterThan(60);
    assertThat(CISCO.schema().relation("bgp-session")).isPresent();
    assertThat(CISCO.schema().derivedFunctions())
        .containsExactly(
            "address-owner",
            "effective-remote-as",
            "link-peer",
            "network-prefix",
            "ospf-area",
            "subnet");
  }

  static Stream<Arguments> sampleLines() {
    return Stream.of(
        Arguments.of("root", "hostname 0_as65002_Linz", "hostname"),
        Arguments.of("root", "interface GigabitEthernet0/0", "interface"),
        Arguments.of("root", "router bgp 65002", "router-bgp"),
        Arguments.of("root", "route-map RM_POLICY_OUT_128_61 deny 50", "route-map"),
        Arguments.of(
            "root",
            "ip prefix-list PL_EXPORT_ALLOWED_128 seq 10 permit 10.200.0.0/24",
            "prefix-list-entry"),
        Arguments.of(
            "root",
            "ip prefix-list PL_IN_129_14 seq 30 permit 10.200.0.0/16 le 24",
            "prefix-list-entry-le"),
        Arguments.of(
            "root",
            "ip prefix-list PL_DENY seq 10 deny 0.0.0.0/0 ge 25 le 32",
            "prefix-list-entry-ge-le"),
        Arguments.of(
            "root", "ip community-list standard CL_COMM_183_75 permit no-export", "community-list"),
        Arguments.of(
            "root",
            "ip community-list standard CL_COMM_142_131 permit 65535:65282",
            "community-list"),
        Arguments.of(
            "root", "ip route 10.200.0.0 255.255.255.0 Loopback100", "static-route-interface"),
        Arguments.of("root", "ip route 0.0.0.0 0.0.0.0 10.0.0.1", "static-route-next-hop"),
        Arguments.of("root", "ip access-list extended ACL_118_GigabitEthernet0_0", "access-list"),
        Arguments.of("interface", "description to 1_as65002_St-Pollen (1)", "description"),
        Arguments.of("interface", "ip address 10.180.0.1 255.255.255.255", "ip-address"),
        Arguments.of("interface", "ip ospf 1 area 0", "ip-ospf-area"),
        Arguments.of("interface", "ip ospf cost 10", "ip-ospf-cost"),
        Arguments.of("interface", "ip ospf network point-to-point", "ip-ospf-network"),
        Arguments.of("interface", "ip router isis 1", "ip-router-isis"),
        Arguments.of("interface", "isis circuit-type level-1", "isis-circuit-type"),
        Arguments.of(
            "interface", "ip access-group ACL_0_GigabitEthernet0_0_OUT out", "ip-access-group"),
        Arguments.of("interface", "shutdown", "interface-shutdown"),
        Arguments.of("router-bgp", "neighbor 10.180.0.11 peer-group RR", "neighbor-peer-group"),
        Arguments.of("router-bgp", "neighbor RR peer-group", "peer-group"),
        Arguments.of("router-bgp", "neighbor RR remote-as 65002", "peer-group-remote-as"),
        Arguments.of(
            "router-bgp", "neighbor RR update-source Loopback0", "peer-group-update-source"),
        Arguments.of("router-bgp", "neighbor 10.181.1.77 remote-as 65001", "neighbor-remote-as"),
        Arguments.of("router-bgp", "no bgp default ipv4-unicast", "no-bgp-default-ipv4"),
        Arguments.of("router-bgp", "address-family ipv4", "address-family"),
        Arguments.of("address-family", "neighbor 10.180.0.11 activate", "neighbor-activate"),
        Arguments.of(
            "address-family",
            "neighbor 10.181.0.60 route-map RM_POLICY_IN_129_14 in",
            "neighbor-route-map"),
        Arguments.of(
            "address-family",
            "neighbor 10.180.0.17 route-reflector-client",
            "neighbor-route-reflector-client"),
        Arguments.of("address-family", "network 10.200.0.0 mask 255.255.255.0", "bgp-network"),
        Arguments.of("address-family", "exit-address-family", "exit-address-family"),
        Arguments.of("router-ospf", "network 10.181.1.50 0.0.0.1 area 0", "ospf-network"),
        Arguments.of("router-ospf", "passive-interface Loopback0", "ospf-passive-interface"),
        Arguments.of(
            "router-ospf",
            "redistribute bgp 65003 metric 90 metric-type 2 subnets route-map RM_BGP",
            "ospf-redistribute-bgp-metric"),
        Arguments.of("router-isis", "net 31.ee01.0000.0ab4.0001.00", "isis-net"),
        Arguments.of("router-isis", "is-type level-1-2", "isis-is-type"),
        Arguments.of(
            "route-map",
            "match ip address prefix-list PL_EXPORT_DENY_LOOPBACK_128_0",
            "match-prefix-list"),
        Arguments.of("route-map", "set as-path prepend 65004 65004 65004", "set-as-path-prepend"),
        Arguments.of(
            "route-map",
            "set community 65000:100 65000:200 no-advertise no-export additive",
            "set-community"),
        Arguments.of("route-map", "set community no-export additive", "set-community"),
        Arguments.of("acl", "10 permit ip host 10.180.0.31 any", "acl-host"),
        Arguments.of("acl", "40 deny ip 169.254.0.0 0.0.255.255 any", "acl-network"),
        Arguments.of("acl", "60 permit ip any any", "acl-any"));
  }

  @ParameterizedTest
  @MethodSource("sampleLines")
  void datasetLineMatchesExactlyOneProduction(String context, String line, String production) {
    Grammar grammar = CISCO.grammar();
    List<Production.Match> matches =
        grammar.match(grammar.context(context).orElseThrow(), Grammar.tokens(line));
    assertThat(matches).hasSize(1);
    assertThat(matches.getFirst().production().id()).isEqualTo(production);
    assertThat(matches.getFirst().render()).isEqualTo(line);
  }

  @Test
  void malformedLinesMatchNothing() {
    Grammar grammar = CISCO.grammar();
    assertThat(
            grammar.match(
                grammar.context("router-ospf").orElseThrow(),
                Grammar.tokens("network 10.180.0.1 0.0.0.0 area")))
        .isEmpty();
    assertThat(
            grammar.match(
                grammar.context("interface").orElseThrow(),
                Grammar.tokens("ip address 10.0.0.1 255.0.255.0")))
        .isEmpty();
  }
}
