package se.kth.sonar.jev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Asks a chat-completion LLM the questions of a Jev decision request, so that an LLM can take Jev's
 * place as the decision model (an ablation). The request's state and choice questions are written
 * into one prompt that asks for the same answer Jev gives, a probability per offered option, as
 * JSON; the LLM's answer is returned in Jev's reply format, so it is decoded, calibrated and used
 * exactly as Jev's would be. The LLM only answers these questions: it neither sees nor writes
 * anything else. Requests go through {@code delegate} to an OpenAI-compatible chat endpoint.
 *
 * <p>The reported output tokens are the total less the prompt tokens, so they include the model's
 * thinking. A reply whose content is not such an answer comes back as a server error, which the
 * decision model retries.
 */
public final class ChatTransport implements DecisionTransport {
  private static final int OK = 200;
  private static final int BAD_GATEWAY = 502;
  private static final String SYSTEM =
      "You are the decision component of a tool that repairs router configurations so that they"
          + " satisfy their specifications. The tool generates candidate edits and verifies them"
          + " itself; your only task is to answer the questions below about the given state, each"
          + " with a probability for every option. Reply with one JSON object and nothing else.";

  private final DecisionTransport delegate;
  private final String model;

  /** Creates a transport that asks {@code model} through {@code delegate}. */
  public ChatTransport(DecisionTransport delegate, String model) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.model = Objects.requireNonNull(model, "model");
  }

  @Override
  public Reply send(Request request) throws IOException, InterruptedException {
    String chat = chatRequest(JevJson.mapper().readTree(request.body()));
    Reply reply = delegate.send(new Request(request.key(), chat));
    if (reply.status() != OK) {
      return reply;
    }
    return jevReply(reply.body());
  }

  /** Returns the chat-completion request that asks {@code jev}'s questions. */
  String chatRequest(JsonNode jev) throws JsonProcessingException {
    StringBuilder prompt = new StringBuilder("# State\n");
    for (Map.Entry<String, JsonNode> section : jev.path("state").properties()) {
      prompt.append("\n## ").append(section.getKey()).append('\n');
      prompt.append(section.getValue().asText()).append('\n');
    }
    prompt.append("\n# Questions\n");
    StringBuilder format = new StringBuilder();
    for (Map.Entry<String, JsonNode> question : jev.path("questions").properties()) {
      prompt.append("\n## Question \"").append(question.getKey()).append("\"\n");
      prompt.append(question.getValue().path("instructions").asText()).append("\n\nOptions:\n");
      for (Map.Entry<String, JsonNode> option : question.getValue().path("criteria").properties()) {
        prompt
            .append("- \"")
            .append(option.getKey())
            .append("\": ")
            .append(option.getValue().asText())
            .append('\n');
      }
      format.append(format.isEmpty() ? "" : ", ");
      format
          .append('"')
          .append(question.getKey())
          .append("\": {\"probabilities\": {\"<option>\": <probability>, ...}}");
    }
    prompt
        .append("\n# Answer\n")
        .append("Reply with {\"answers\": {")
        .append(format)
        .append("}}. Give every option of a question a probability, name the options exactly as")
        .append(" quoted above, and let the probabilities of each question sum to 1.\n");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put(
        "messages",
        List.of(
            Map.of("role", "system", "content", SYSTEM),
            Map.of("role", "user", "content", prompt.toString())));
    body.put("response_format", Map.of("type", "json_object"));
    return JevJson.mapper().writeValueAsString(body);
  }

  /** Returns the chat reply {@code body} in Jev's reply format. */
  static Reply jevReply(String body) throws JsonProcessingException {
    JsonNode chat = JevJson.mapper().readTree(body);
    long input = chat.path("usage").path("prompt_tokens").asLong(0);
    long total = chat.path("usage").path("total_tokens").asLong(0);
    long output = Math.max(chat.path("usage").path("completion_tokens").asLong(0), total - input);
    Map<String, Object> reply = new LinkedHashMap<>();
    reply.put("usage", Map.of("input_tokens", input, "output_tokens", output));
    String content = chat.path("choices").path(0).path("message").path("content").asText("");
    JsonNode answers = answers(content);
    if (answers == null) {
      reply.put("error", "the reply holds no answers: " + abbreviated(content));
      return new Reply(BAD_GATEWAY, JevJson.mapper().writeValueAsString(reply));
    }
    reply.put("answers", answers);
    return new Reply(OK, JevJson.mapper().writeValueAsString(reply));
  }

  private static JsonNode answers(String content) {
    String json = content.strip();
    int open = json.indexOf('{');
    int close = json.lastIndexOf('}');
    if (open < 0 || close < open) {
      return null;
    }
    try {
      JsonNode answers = JevJson.mapper().readTree(json.substring(open, close + 1)).path("answers");
      return answers.isObject() ? answers : null;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static String abbreviated(String text) {
    return text.length() <= 200 ? text : text.substring(0, 200) + "...";
  }
}
