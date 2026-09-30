package se.kth.sonar.program;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.text.RouterId;

class ProgramModelTest {
  private static final String R1 =
      """
      hostname r1
      interface Loopback0
       ip address 10.0.0.1 255.255.255.255
      interface GigabitEthernet0/0
       ip address 10.1.0.1 255.255.255.254
       ip ospf 1 area 0
       ip access-group EDGE in
      router ospf 1
       router-id 10.0.0.1
       network 10.1.0.0 0.0.0.1 area 0
      router bgp 65001
       bgp router-id 10.0.0.1
       neighbor IBGP peer-group
       neighbor IBGP remote-as 65001
       neighbor 10.0.0.2 peer-group IBGP
       neighbor 10.0.0.9 remote-as 65001
       address-family ipv4
        neighbor 10.0.0.2 activate
        neighbor 10.0.0.2 route-map OUT out
       exit-address-family
      """;
  private static final String R2 =
      """
      hostname r2
      interface Loopback0
       ip address 10.0.0.2 255.255.255.255
      interface GigabitEthernet0/0
       ip address 10.1.0.0 255.255.255.254
      router ospf 1
       network 10.1.0.0 0.0.0.1 area 1
      router bgp 65001
       neighbor 10.0.0.1 remote-as 65009
       address-family ipv4
        neighbor 10.0.0.1 activate
       exit-address-family
      """;
  private static final Dialect DIALECT = Dialect.load("cisco-ios");
  private static ExecutionContext context;
  private static ProgramModel model;

  static ProgramModel model() {
    return model;
  }

  @BeforeAll
  static void build() {
    context = ExecutionContext.open(ParallelismConfig.sequential(), List.of());
    Snapshot snapshot = Snapshot.of(Map.of("1_as65001_r1.cfg", R1, "2_as65001_r2.cfg", R2));
    Topology topology =
        Topology.of(
            snapshot.routers(),
            List.of(
                new Topology.Link(
                    new Topology.Endpoint(RouterId.of("1_as65001_r1"), "GigabitEthernet0/0"),
                    new Topology.Endpoint(RouterId.of("2_as65001_r2"), "GigabitEthernet0/0"))));
    model =
        new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context)
            .model(snapshot, topology);
  }

  @AfterAll
  static void close() {
    context.close();
  }

  @Test
  void bindsStatementsToTypedObjects() {
    ProgramObject r1 = object("router:1_as65001_r1");
    assertThat(r1.value("bgp-asn")).contains("65001");
    assertThat(r1.value("bgp-router-id")).contains("10.0.0.1");
    assertThat(r1.value("ospf-process")).contains("1");
    assertThat(r1.value("design-asn")).contains("65001");
    ProgramObject link = object("interface:1_as65001_r1:GigabitEthernet0/0");
    assertThat(link.value("subnet")).contains("10.1.0.0/31");
    assertThat(link.value("ospf-effective-area")).contains("0");
    assertThat(link.value("link-peer")).contains("interface:2_as65001_r2:GigabitEthernet0/0");
    assertThat(link.value("acl-in")).contains("EDGE");
    assertThat(object("interface:2_as65001_r2:GigabitEthernet0/0").value("ospf-effective-area"))
        .contains("1");
    ProgramObject neighbor = object("bgp-neighbor:1_as65001_r1:10.0.0.2");
    assertThat(neighbor.value("peer-group")).contains("IBGP");
    assertThat(neighbor.value("effective-remote-as")).contains("65001");
    assertThat(neighbor.has("activated")).isTrue();
    assertThat(neighbor.value("route-map-out")).contains("OUT");
    assertThat(neighbor.value("peer-router")).contains("router:2_as65001_r2");
    assertThat(neighbor.statements()).hasSize(3);
    assertThat(object("peer-group:1_as65001_r1:IBGP").value("remote-as")).contains("65001");
    assertThat(model.dangling()).isEmpty();
  }

  @Test
  void findsRelationInstancesAndTheirViolations() {
    List<RelationInstance> sessions = model.instances("bgp-session");
    assertThat(sessions).hasSize(1);
    assertThat(sessions.getFirst().roles()).hasSize(2);
    assertThat(sessions.getFirst().violated())
        .extracting(requirement -> requirement.constraint().toString())
        .singleElement()
        .asString()
        .contains("effective-remote-as");
    RelationInstance lonely =
        model.instances("bgp-policy").stream()
            .filter(instance -> instance.roles().get("neighbor").key().equals(List.of("10.0.0.9")))
            .findFirst()
            .orElseThrow();
    assertThat(lonely.violated())
        .extracting(requirement -> requirement.constraint().toString())
        .containsExactly("Exists[path=neighbor.peer-router]");
    assertThat(lonely.violated().getFirst().strength()).isEqualTo(Requirement.Strength.HARD);
    assertThat(model.instances("bgp-design")).allMatch(RelationInstance::satisfied);
    List<RelationInstance> links = model.instances("link");
    assertThat(links).hasSize(1);
    assertThat(links.getFirst().violated())
        .extracting(requirement -> requirement.constraint().toString())
        .anySatisfy(value -> assertThat(value).contains("ospf-effective-area"))
        .anySatisfy(value -> assertThat(value).contains("ospf-cost"));
    assertThat(model.instances("bgp-policy"))
        .filteredOn(instance -> !instance.satisfied())
        .extracting(instance -> instance.roles().get("neighbor").toString())
        .containsExactly(
            "bgp-neighbor:1_as65001_r1:10.0.0.2", "bgp-neighbor:1_as65001_r1:10.0.0.9");
    assertThat(model.instances("interface-filter"))
        .filteredOn(instance -> !instance.satisfied())
        .hasSize(1);
    assertThat(model.instances("router-identity")).allMatch(RelationInstance::satisfied);
  }

  private static ProgramObject object(String ref) {
    return model.object(ProgramObject.Ref.parse(ref)).orElseThrow();
  }
}
