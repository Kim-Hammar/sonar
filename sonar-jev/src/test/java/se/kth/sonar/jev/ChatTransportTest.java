package se.kth.sonar.jev;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import se.kth.sonar.common.ContentHash;

class ChatTransportTest {
  private static final String JEV_REQUEST =
      """
      {"model":"gemini-3.8-flash",
       "questions":{"decision":{"type":"choice","instructions":"Which region?",
         "criteria":{"site:r1":"the BGP process of r1","none":"No region."}}},
       "state":{"1. Violations":"r1 cannot reach r2"}}""";

  private static DecisionTransport.Request request() {
    return new DecisionTransport.Request(ContentHash.of(JEV_REQUEST), JEV_REQUEST);
  }

  private static String chatReply(String content) throws JsonProcessingException {
    return """
        {"choices":[{"message":{"content":%s}}],
         "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":130}}"""
        .formatted(JevJson.mapper().writeValueAsString(content));
  }

  @Test
  void asksTheQuestionsInOnePromptAndAnswersInJevsFormat() throws Exception {
    AtomicReference<String> sent = new AtomicReference<>();
    String reply =
        chatReply(
            "```json\n{\"answers\": {\"decision\": {\"probabilities\":"
                + " {\"site:r1\": 0.8, \"none\": 0.2}}}}\n```");
    ChatTransport transport =
        new ChatTransport(
            request -> {
              sent.set(request.body());
              return new DecisionTransport.Reply(200, reply);
            },
            "gemini-3.8-flash");
    DecisionTransport.Reply answered = transport.send(request());

    JsonNode chat = JevJson.mapper().readTree(sent.get());
    assertThat(chat.path("model").asText()).isEqualTo("gemini-3.8-flash");
    String prompt = chat.path("messages").path(1).path("content").asText();
    assertThat(prompt)
        .contains("r1 cannot reach r2", "Which region?", "\"site:r1\": the BGP process of r1");
    assertThat(answered.status()).isEqualTo(200);
    JsonNode jev = JevJson.mapper().readTree(answered.body());
    assertThat(
            jev.path("answers").path("decision").path("probabilities").path("site:r1").asDouble())
        .isEqualTo(0.8);
    assertThat(JevRequestCodec.usageOf(answered).inputTokens()).isEqualTo(100);
    assertThat(JevRequestCodec.usageOf(answered).outputTokens()).isEqualTo(30);
  }

  @Test
  void aReplyWithoutAnswersIsAServerErrorThatStillReportsItsUsage() throws Exception {
    ChatTransport transport =
        new ChatTransport(
            request -> new DecisionTransport.Reply(200, chatReply("I cannot decide.")),
            "gemini-3.8-flash");
    DecisionTransport.Reply failed = transport.send(request());
    assertThat(failed.status()).isEqualTo(502);
    assertThat(JevRequestCodec.usageOf(failed).inputTokens()).isEqualTo(100);
  }

  @Test
  void aProviderErrorPassesThrough() throws Exception {
    ChatTransport transport =
        new ChatTransport(
            request -> new DecisionTransport.Reply(429, "{\"error\":\"slow down\"}"),
            "gemini-3.8-flash");
    assertThat(transport.send(request()).status()).isEqualTo(429);
  }
}
