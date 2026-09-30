package se.kth.sonar.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

class JevDecisionModelTest {
  private static final RouterId R1 = RouterId.of("r1");
  private static final Specification TARGET =
      new Specification(
          new ForwardingPredicate.Reachability(R1, Prefix.parse("10.0.0.0/24")),
          Specification.Status.parse("broken_removed"));
  private static final String ANSWER =
      "{\"answers\": {\"decision\": {\"probabilities\": {\"a\": 0.7, \"none\": 0.3}}},"
          + " \"usage\": {\"input_tokens\": 50, \"output_tokens\": 5}}";

  /** Replies in a scripted order and records what it received. */
  private static final class ScriptedTransport implements DecisionTransport {
    final Deque<Reply> replies = new ArrayDeque<>();
    final List<Request> received = new java.util.ArrayList<>();

    @Override
    public Reply send(Request request) throws IOException {
      received.add(request);
      Reply reply = replies.poll();
      if (reply == null) {
        throw new IOException("connection refused");
      }
      return reply;
    }
  }

  private static DecisionProblem.RegionChoice problem() {
    Region region =
        new SiteRegion(
            new Region.Id("a"),
            new ProgramObject.Ref("interface", R1, List.of("a")),
            List.of(TARGET),
            Map.of(),
            new Region.Hypotheses.Deferred(2));
    Partition partition =
        new Partition(new Partition.Id("p"), region.signature(), List.of(region.id()));
    SequencedMap<String, String> options = new LinkedHashMap<>();
    options.put("a", "edit interface a");
    return new DecisionProblem.RegionChoice(
        partition,
        List.of(region),
        options,
        new DecisionProblem.Context(
            List.of(new DecisionProblem.Context.Section("Setting", "one router"))));
  }

  @Test
  void retriesRateLimitsAndServerErrorsThenAnswers() {
    ScriptedTransport transport = new ScriptedTransport();
    transport.replies.add(new DecisionTransport.Reply(429, "{\"error\": \"slow down\"}"));
    transport.replies.add(new DecisionTransport.Reply(503, "{\"error\": \"busy\"}"));
    transport.replies.add(new DecisionTransport.Reply(200, ANSWER));
    JevDecisionModel model = new JevDecisionModel(transport, new JevRequestCodec("jev"));
    DecisionOutcome<RegionAssessment> outcome = model.assess(problem());
    assertThat(outcome).isInstanceOf(DecisionOutcome.Answered.class);
    assertThat(outcome.usage()).isEqualTo(new DecisionOutcome.Usage(3, 50, 5));
    assertThat(outcome.exchanges()).hasSize(3);
    assertThat(outcome.exchanges().getLast().status()).isEqualTo(200);
    assertThat(transport.received).hasSize(3);
  }

  @Test
  void clientErrorsAndExhaustedRetriesAreUnavailable() {
    ScriptedTransport transport = new ScriptedTransport();
    transport.replies.add(new DecisionTransport.Reply(400, "{\"error\": \"bad request\"}"));
    JevDecisionModel model = new JevDecisionModel(transport, new JevRequestCodec("jev"));
    DecisionOutcome<RegionAssessment> outcome = model.assess(problem());
    assertThat(outcome).isInstanceOf(DecisionOutcome.Unavailable.class);
    assertThat(((DecisionOutcome.Unavailable<RegionAssessment>) outcome).reason()).contains("400");
    assertThat(transport.received).hasSize(1);
    ScriptedTransport refusing = new ScriptedTransport();
    DecisionOutcome<RegionAssessment> failed =
        new JevDecisionModel(refusing, new JevRequestCodec("jev")).assess(problem());
    assertThat(failed).isInstanceOf(DecisionOutcome.Unavailable.class);
    assertThat(failed.usage().providerCalls()).isEqualTo(4);
  }

  @Test
  void oversizedQuestionsAreNotSent() {
    ScriptedTransport transport = new ScriptedTransport();
    JevDecisionModel model = new JevDecisionModel(transport, new JevRequestCodec("jev"), 40);
    DecisionOutcome<RegionAssessment> outcome = model.assess(problem());
    assertThat(outcome).isInstanceOf(DecisionOutcome.TooLarge.class);
    assertThat(((DecisionOutcome.TooLarge<RegionAssessment>) outcome).limit()).isEqualTo(40);
    assertThat(transport.received).isEmpty();
  }

  @Test
  void cachedRepliesCostNoProviderCalls(@TempDir Path directory) throws IOException {
    ScriptedTransport transport = new ScriptedTransport();
    transport.replies.add(new DecisionTransport.Reply(200, ANSWER));
    CachingTransport caching = new CachingTransport(transport, directory.resolve("cache"));
    JevDecisionModel model = new JevDecisionModel(caching, new JevRequestCodec("jev"));
    DecisionOutcome<RegionAssessment> first = model.assess(problem());
    DecisionOutcome<RegionAssessment> second = model.assess(problem());
    assertThat(first.usage().providerCalls()).isEqualTo(1);
    assertThat(second.usage()).isEqualTo(DecisionOutcome.Usage.none());
    assertThat(second.exchanges().getFirst().cached()).isTrue();
    assertThat(transport.received).hasSize(1);
    try (var files = Files.list(directory.resolve("cache"))) {
      assertThat(files.filter(f -> f.toString().endsWith(".json"))).hasSize(1);
    }
  }
}
