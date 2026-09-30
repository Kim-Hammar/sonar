package se.kth.sonar.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

class JevRequestCodecTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final Specification TARGET =
      new Specification(
          new ForwardingPredicate.Reachability(R1, Prefix.parse("10.0.0.0/24")),
          Specification.Status.parse("broken_removed"));
  private static final DecisionProblem.Context CONTEXT =
      new DecisionProblem.Context(
          List.of(
              new DecisionProblem.Context.Section("Setting", "two routers"),
              new DecisionProblem.Context.Section("Violated specifications", "one")));

  private final JevRequestCodec codec = new JevRequestCodec("jev-test");

  private static Region region(String id) {
    return new SiteRegion(
        new Region.Id(id),
        new ProgramObject.Ref("interface", R1, List.of(id)),
        List.of(TARGET),
        Map.of(),
        new Region.Hypotheses.Deferred(2));
  }

  private static DecisionProblem.RegionChoice regionChoice() {
    List<Region> regions = List.of(region("a"), region("b"));
    Partition partition =
        new Partition(
            new Partition.Id("p"),
            regions.getFirst().signature(),
            List.of(new Region.Id("a"), new Region.Id("b")));
    SequencedMap<String, String> options = new LinkedHashMap<>();
    options.put("a", "edit interface a");
    options.put("b", "edit interface b");
    return new DecisionProblem.RegionChoice(partition, regions, options, CONTEXT);
  }

  @Test
  void encodesARegionChoiceAsTwoQuestionsWithANoneOption() throws Exception {
    DecisionTransport.Request request = codec.encode(regionChoice());
    JsonNode root = JevJson.mapper().readTree(request.body());
    assertThat(root.path("model").asText()).isEqualTo("jev-test");
    assertThat(root.path("state").path("1. Setting").asText()).isEqualTo("two routers");
    JsonNode decision = root.path("questions").path("decision");
    assertThat(decision.path("type").asText()).isEqualTo("choice");
    assertThat(decision.path("criteria").path("none").asText()).contains("None of these regions");
    assertThat(decision.path("criteria").path("a").asText()).isEqualTo("edit interface a");
    JsonNode conditional = root.path("questions").path("region_given_relevant");
    assertThat(conditional.path("criteria").has("none")).isFalse();
    assertThat(conditional.path("criteria").size()).isEqualTo(2);
    assertThat(request.key()).isEqualTo(ContentHash.of(request.body()));
    assertThat(codec.encode(regionChoice()).body()).isEqualTo(request.body());
  }

  @Test
  void decodesAnAssessmentFromBothAnswers() {
    String reply =
        """
        {"answers": {"decision": {"probabilities": {"a": 0.5, "b": 0.3, "none": 0.2}},
                     "region_given_relevant": {"probabilities": {"a": 0.9, "b": 0.1}}},
         "usage": {"input_tokens": 120, "output_tokens": 9, "cost": 0.001}}
        """;
    DecisionOutcome<RegionAssessment> outcome =
        codec.decodeAssessment(
            regionChoice(),
            new DecisionTransport.Reply(200, reply),
            new DecisionOutcome.Usage(1, 120, 9),
            List.of());
    assertThat(outcome).isInstanceOf(DecisionOutcome.Answered.class);
    RegionAssessment assessment = ((DecisionOutcome.Answered<RegionAssessment>) outcome).answer();
    assertThat(assessment.none()).isCloseTo((0.2 + 0.5) / 2.5, within(1e-12));
    assertThat(assessment.regions().probability(new Region.Id("a")))
        .isCloseTo((0.9 + 0.5) / 2, within(1e-12));
    assertThat(assessment.regions().probability(new Region.Id("b")))
        .isCloseTo((0.1 + 0.5) / 2, within(1e-12));
  }

  @Test
  void repliesWithoutTheOfferedOptionsAreUnavailable() {
    DecisionOutcome<RegionAssessment> outcome =
        codec.decodeAssessment(
            regionChoice(),
            new DecisionTransport.Reply(
                200, "{\"answers\": {\"decision\": {\"probabilities\": {\"x\": 1}}}}"),
            DecisionOutcome.Usage.none(),
            List.of());
    assertThat(outcome).isInstanceOf(DecisionOutcome.Unavailable.class);
    assertThat(
            codec.decodeAssessment(
                regionChoice(),
                new DecisionTransport.Reply(200, "not json"),
                DecisionOutcome.Usage.none(),
                List.of()))
        .isInstanceOf(DecisionOutcome.Unavailable.class);
  }

  @Test
  void readsUsageFromReplies() {
    DecisionOutcome.Usage usage =
        JevRequestCodec.usageOf(
            new DecisionTransport.Reply(
                200, "{\"usage\": {\"input_tokens\": 7, \"output_tokens\": 3}}"));
    assertThat(usage).isEqualTo(new DecisionOutcome.Usage(1, 7, 3));
    assertThat(JevRequestCodec.usageOf(new DecisionTransport.Reply(500, "oops")))
        .isEqualTo(new DecisionOutcome.Usage(1, 0, 0));
  }

  @Test
  void decodesARankingInProbabilityOrder() {
    HypothesisRanking ranking =
        HypothesisRanking.by(
            se.kth.sonar.common.Distribution.of(
                Map.of(ContentHash.of("x"), 0.2, ContentHash.of("y"), 0.8)));
    assertThat(ranking.order()).containsExactly(ContentHash.of("y"), ContentHash.of("x"));
  }
}
