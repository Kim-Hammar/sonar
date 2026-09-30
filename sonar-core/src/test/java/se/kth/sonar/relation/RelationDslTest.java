package se.kth.sonar.relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import se.kth.sonar.common.DslException;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.GrammarDsl;

class RelationDslTest {
  private static final Grammar GRAMMAR =
      GrammarDsl.parse(
          """
          grammar small
          sort asn integer 1..65535
          sort word name
          sort address ipv4
          sort mask mask
          sort interface interface Loopback GigabitEthernet
          sort direction enum in out
          context root
          context interface in root
          context router-bgp in root
          production hostname root : hostname <name:word>
          production interface root opens interface : interface <name:interface>
          production ip-address interface : ip address <address:address> <mask:mask>
          production router-bgp root opens router-bgp : router bgp <asn:asn>
          production neighbor router-bgp : neighbor <peer:address> remote-as <asn:asn>
          production neighbor-map router-bgp : neighbor <peer:address> route-map <map:word> <direction:direction>
          production passive router-bgp : passive <interface:interface>
          production route-map root : route-map <name:word>
          """,
          "small.grammar");

  private static final String SCHEMA =
      """
      relations small
      object router
        attribute hostname = hostname.name
        attribute asn = router-bgp.asn
      object interface
        key name from interface
        attribute address = ip-address.address
        attribute passive flag passive by interface
      object route-map
        key name from route-map
      object neighbor
        key peer from neighbor
        attribute remote-as = neighbor.asn
        attribute map-in ref route-map = neighbor-map.map where direction = in
        attribute peer-router derived address-owner ref router
      relation session symmetric
        role local neighbor each
        role remote neighbor optional
        join remote.router = local.peer-router
        join local.router = remote.peer-router
        require hard exists remote
        require hard equal local.remote-as remote.router.asn
      relation policy
        role neighbor neighbor each
        require hard resolves neighbor.map-in when neighbor.remote-as
      domain neighbor asn related session remote.router.asn
      domain neighbor-map direction literals
      domain route-map name fresh
      """;

  @Test
  void parsesObjectsRelationsAndDomains() {
    RelationSchema schema = RelationDsl.parse(SCHEMA, "small.relations", GRAMMAR);
    ObjectType neighbor = schema.object("neighbor").orElseThrow();
    assertThat(neighbor.key()).containsExactly("peer");
    assertThat(neighbor.attributes().keySet())
        .containsExactly("peer", "router", "remote-as", "map-in", "peer-router");
    assertThat(neighbor.attribute("map-in").orElseThrow().type())
        .isEqualTo(new Attribute.Type.Reference("route-map"));
    assertThat(neighbor.attribute("router").orElseThrow().source())
        .isEqualTo(new Attribute.Source.Owner());
    assertThat(schema.router().isRouter()).isTrue();
    assertThat(schema.object("interface").orElseThrow().attribute("passive").orElseThrow().type())
        .isEqualTo(new Attribute.Type.Flag());
    RelationType session = schema.relation("session").orElseThrow();
    assertThat(session.symmetric()).isTrue();
    assertThat(session.driver().name()).isEqualTo("local");
    assertThat(session.requirements()).hasSize(2);
    assertThat(schema.relation("policy").orElseThrow().requirements().getFirst().guard())
        .contains(AttributePath.parse("neighbor.remote-as"));
    assertThat(schema.derivedFunctions()).containsExactly("address-owner");
    assertThat(schema.domains())
        .extracting(DomainRule::support)
        .containsExactly(ValueSupport.EVIDENCED, ValueSupport.OBSERVED, ValueSupport.INVENTED);
    assertThat(schema.domains(GRAMMAR.production("route-map").orElseThrow())).hasSize(1);
  }

  static Stream<Arguments> errors() {
    return Stream.of(
        Arguments.of("  attribute bogus = nowhere.slot", "unknown production 'nowhere'"),
        Arguments.of("  attribute bogus = neighbor.nothing", "has no slot 'nothing'"),
        Arguments.of("  attribute bogus flag passive", "production passive has no slot 'peer'"),
        Arguments.of("  attribute remote-as = neighbor.asn", "already has attribute 'remote-as'"),
        Arguments.of(
            "  attribute bogus ref neighbor = neighbor-map.map", "cannot name a 'neighbor'"),
        Arguments.of(
            "  attribute bogus = neighbor-map.map where direction = sideways",
            "'sideways' is not a direction"),
        Arguments.of(
            "relation broken\n  role n neighbor each\n  require hard resolves n.remote-as",
            "needs a path that ends at a reference"),
        Arguments.of(
            "relation broken\n  role n neighbor each\n  require hard exists m.remote-as",
            "unknown role 'm'"),
        Arguments.of(
            "relation broken\n  role n neighbor each\n  require hard equal n.remote-as n.peer-router",
            "incompatible types"),
        Arguments.of(
            "relation broken\n  role n neighbor each\n  role m neighbor optional",
            "no join determines role 'm'"),
        Arguments.of(
            "relation broken symmetric\n  role n neighbor each\n  role m interface optional\n  join m.router = n.router",
            "same object type"),
        Arguments.of(
            "relation broken\n  role n neighbor optional",
            "the first role and only the first role is 'each'"),
        Arguments.of("domain neighbor asn literals", "literals fill one enumeration slot"),
        Arguments.of("domain neighbor asn names route-map in router", "cannot name a 'route-map'"),
        Arguments.of("domain neighbor peer verifier destination", "a prefix fills a prefix slot"),
        Arguments.of("key name from route-map", "'key' outside an object"));
  }

  @ParameterizedTest
  @MethodSource("errors")
  void reportsErrorsAtTheirLine(String appended, String message) {
    String source = SCHEMA.replace("relation session", appended + "\nrelation session");
    int line = (int) SCHEMA.substring(0, SCHEMA.indexOf("relation session")).lines().count() + 1;
    if (appended.startsWith("relation")
        || appended.startsWith("domain")
        || appended.startsWith("key")) {
      source = SCHEMA + appended + "\n";
      line = (int) SCHEMA.lines().count() + (int) appended.lines().count();
    }
    String schema = source;
    int expected = line;
    assertThatThrownBy(() -> RelationDsl.parse(schema, "test.relations", GRAMMAR))
        .isInstanceOfSatisfying(
            DslException.class,
            error -> {
              assertThat(error.getMessage()).contains(message);
              if (!message.contains("no join") && !message.contains("same object type")) {
                assertThat(error.line()).isEqualTo(expected);
              }
            });
  }

  @Test
  void requiresTheRouterObject() {
    assertThatThrownBy(() -> RelationDsl.parse("relations empty\n", "empty.relations", GRAMMAR))
        .isInstanceOf(DslException.class)
        .hasMessageContaining("must declare object 'router'");
  }
}
