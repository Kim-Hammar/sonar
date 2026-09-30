package se.kth.sonar.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

class SearchJsonTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final Snapshot BASE =
      Snapshot.of(
          Map.of(
              "r1.cfg",
              """
              hostname r1
              interface Loopback0
               ip address 10.0.0.1 255.255.255.255
              router bgp 65001
               neighbor 10.0.0.2 remote-as 65009
              """));

  private static Hypothesis hypothesis() {
    return new Hypothesis(
        Transaction.of(
            BASE,
            List.of(
                new LineEdit.Replace(
                    new SourceSpan(R1, 5, 5), List.of(" neighbor 10.0.0.2 remote-as 65001")),
                new LineEdit.Delete(new SourceSpan(R1, 2, 3)),
                new LineEdit.Append(R1, List.of("router ospf 1")))),
        List.of());
  }

  @Test
  void writesEditsWithTheRemovedTextWhenGivenTheBase() {
    JsonNode node = SearchJson.hypothesis(hypothesis(), BASE);
    assertThat(node.path("routers")).extracting(JsonNode::asText).containsExactly("r1");
    assertThat(node.path("support").asText()).isEqualTo("evidenced");
    assertThat(node.path("restores")).isEmpty();
    JsonNode edits = node.path("edits");
    assertThat(edits).hasSize(3);
    JsonNode replace = edits.get(0);
    assertThat(replace.path("op").asText()).isEqualTo("replace");
    assertThat(replace.path("first").asInt()).isEqualTo(5);
    assertThat(replace.path("removed"))
        .extracting(JsonNode::asText)
        .containsExactly(" neighbor 10.0.0.2 remote-as 65009");
    assertThat(replace.path("lines"))
        .extracting(JsonNode::asText)
        .containsExactly(" neighbor 10.0.0.2 remote-as 65001");
    JsonNode delete = edits.get(1);
    assertThat(delete.path("op").asText()).isEqualTo("delete");
    assertThat(delete.path("removed"))
        .extracting(JsonNode::asText)
        .containsExactly("interface Loopback0", " ip address 10.0.0.1 255.255.255.255");
    assertThat(edits.get(2).path("op").asText()).isEqualTo("append");
    assertThat(edits.get(2).has("removed")).isFalse();
  }

  @Test
  void leavesTheRemovedTextOutWithoutTheBase() {
    JsonNode edits = SearchJson.hypothesis(hypothesis()).path("edits");
    assertThat(edits.get(0).has("removed")).isFalse();
    assertThat(edits.get(0).path("last").asInt()).isEqualTo(5);
    assertThat(edits.get(1).has("removed")).isFalse();
  }

  @Test
  void namesTheKindOfARegion() {
    Specification target =
        new Specification(
            new ForwardingPredicate.Reachability(R1, Prefix.parse("10.0.0.2/32")),
            Specification.Status.parse("broken_removed"));
    ProgramObject.Ref ref = ProgramObject.Ref.parse("interface:r1:Loopback0");
    Region site =
        new SiteRegion(
            new Region.Id("site:" + ref),
            ref,
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(3));
    Region relation =
        new RelationRegion(
            new Region.Id("relation:link:abc"),
            new RelationInstance("link", Map.of("a", ref), List.of(), Set.of()),
            List.of(target),
            Map.of(),
            new Region.Hypotheses.Deferred(16));
    assertThat(SearchJson.kind(site)).isEqualTo("site");
    assertThat(SearchJson.kind(relation)).isEqualTo("relation");
  }
}
