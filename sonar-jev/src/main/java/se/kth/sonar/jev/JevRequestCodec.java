package se.kth.sonar.jev;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.search.Region;

/**
 * Encodes decision problems as Jev requests and decodes Jev's replies.
 *
 * <p>A request carries the model, the context sections as the state, and typed choice questions
 * over the options. A region choice asks two questions: which region holds the repair, with a none
 * option, and which region holds it given that one does; the answers combine as {@code P(none)} and
 * {@code (1 - P(none)) * P(region | one does)}. A hypothesis choice asks one question, and the
 * candidates are ordered by the answer's probabilities. One answer is one soft categorical
 * observation, so its probabilities are calibrated with the Jeffreys prior, {@code (p + 1/2) / (1 +
 * n/2)} over {@code n} options, which keeps every option possible. Replies that do not name the
 * offered options decode as unavailable.
 */
public final class JevRequestCodec {
  static final String NONE = "none";
  private static final String REGION_INSTRUCTIONS =
      "Which option is most likely to contain an edit that fixes at least one of the violated "
          + "specifications? Select "
          + NONE
          + " only when none of the regions fixes any of them.";
  private static final String CONDITIONAL_INSTRUCTIONS =
      "Assuming that at least one of the regions contains a repair, select the most likely region.";
  private static final String NONE_DESCRIPTION =
      "None of these regions fixes any violated specification.";
  private static final String RANK_INSTRUCTIONS =
      "Which candidate edit is most likely to fix at least one violated specification without "
          + "breaking others? Prefer the smallest edit that explains the verifier evidence and "
          + "respects the program relations.";

  private final String model;

  /** Creates a codec that addresses requests to {@code model}. */
  public JevRequestCodec(String model) {
    this.model = Objects.requireNonNull(model, "model");
  }

  /** Returns the request for {@code problem}. */
  public DecisionTransport.Request encode(DecisionProblem problem) {
    Map<String, Object> questions = new LinkedHashMap<>();
    switch (problem) {
      case DecisionProblem.RegionChoice choice -> {
        SequencedMap<String, String> withNone = new LinkedHashMap<>(choice.options());
        withNone.put(NONE, NONE_DESCRIPTION);
        questions.put("decision", question(REGION_INSTRUCTIONS, withNone));
        if (choice.options().size() > 1) {
          questions.put(
              "region_given_relevant", question(CONDITIONAL_INSTRUCTIONS, choice.options()));
        }
      }
      case DecisionProblem.HypothesisChoice choice ->
          questions.put("decision", question(RANK_INSTRUCTIONS, choice.options()));
    }
    Map<String, String> state = new LinkedHashMap<>();
    List<DecisionProblem.Context.Section> sections = problem.context().sections();
    for (int at = 0; at < sections.size(); at++) {
      state.put((at + 1) + ". " + sections.get(at).title(), sections.get(at).body());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", model);
    payload.put("state", state);
    payload.put("questions", questions);
    try {
      String body = JevJson.mapper().writeValueAsString(payload);
      return new DecisionTransport.Request(ContentHash.of(body), body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot encode a decision request", e);
    }
  }

  private static Map<String, Object> question(String instructions, Map<String, String> criteria) {
    Map<String, Object> question = new LinkedHashMap<>();
    question.put("type", "choice");
    question.put("instructions", instructions);
    question.put("criteria", criteria);
    return question;
  }

  /** Decodes the reply to a region choice. */
  public DecisionOutcome<RegionAssessment> decodeAssessment(
      DecisionProblem.RegionChoice problem,
      DecisionTransport.Reply reply,
      DecisionOutcome.Usage usage,
      List<DecisionOutcome.Exchange> exchanges) {
    try {
      JsonNode root = JevJson.mapper().readTree(reply.body());
      Map<String, Double> decision =
          calibrated(probabilities(root, "decision", withNone(problem.options().keySet())));
      double none = decision.getOrDefault(NONE, 0.0);
      Map<Region.Id, Double> regions = new LinkedHashMap<>();
      if (root.path("answers").has("region_given_relevant")) {
        Map<String, Double> conditional =
            calibrated(probabilities(root, "region_given_relevant", problem.options().keySet()));
        for (Region region : problem.regions()) {
          regions.put(region.id(), conditional.getOrDefault(region.id().value(), 0.0));
        }
      } else {
        for (Region region : problem.regions()) {
          regions.put(region.id(), decision.getOrDefault(region.id().value(), 0.0));
        }
      }
      return new DecisionOutcome.Answered<>(
          new RegionAssessment(Distribution.of(regions), none), usage, exchanges);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return new DecisionOutcome.Unavailable<>(
          "the reply is not a usable assessment: " + e.getMessage(), usage, exchanges);
    }
  }

  /** Decodes the reply to a hypothesis choice. */
  public DecisionOutcome<HypothesisRanking> decodeRanking(
      DecisionProblem.HypothesisChoice problem,
      DecisionTransport.Reply reply,
      DecisionOutcome.Usage usage,
      List<DecisionOutcome.Exchange> exchanges) {
    try {
      JsonNode root = JevJson.mapper().readTree(reply.body());
      Map<String, Double> decision = probabilities(root, "decision", problem.options().keySet());
      Map<ContentHash, Double> weights = new LinkedHashMap<>();
      for (Hypothesis hypothesis : problem.hypotheses()) {
        weights.put(hypothesis.id(), decision.getOrDefault(hypothesis.id().abbreviated(), 0.0));
      }
      return new DecisionOutcome.Answered<>(
          HypothesisRanking.by(Distribution.of(weights)), usage, exchanges);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return new DecisionOutcome.Unavailable<>(
          "the reply is not a usable ranking: " + e.getMessage(), usage, exchanges);
    }
  }

  /** Reads the usage a reply reports, or zero usage when it reports none. */
  public static DecisionOutcome.Usage usageOf(DecisionTransport.Reply reply) {
    try {
      JsonNode usage = JevJson.mapper().readTree(reply.body()).path("usage");
      return new DecisionOutcome.Usage(
          1, usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
    } catch (JsonProcessingException e) {
      return new DecisionOutcome.Usage(1, 0, 0);
    }
  }

  private static Set<String> withNone(Set<String> options) {
    Set<String> all = new LinkedHashSet<>(options);
    all.add(NONE);
    return all;
  }

  /**
   * Returns the normalised probabilities of {@code question}'s answer over {@code options}.
   *
   * @throws IllegalArgumentException if the answer names none of the options
   */
  private static Map<String, Double> probabilities(
      JsonNode root, String question, Set<String> options) {
    JsonNode answer = root.path("answers").path(question).path("probabilities");
    if (!answer.isObject()) {
      throw new IllegalArgumentException("no probabilities for question " + question);
    }
    Map<String, Double> raw = new LinkedHashMap<>();
    double total = 0;
    for (String option : options) {
      double value = Math.max(0, answer.path(option).asDouble(0));
      raw.put(option, value);
      total += value;
    }
    if (!(total > 0)) {
      throw new IllegalArgumentException("the answer to " + question + " names no option");
    }
    for (Map.Entry<String, Double> entry : raw.entrySet()) {
      entry.setValue(entry.getValue() / total);
    }
    return raw;
  }

  /** Treats one answer as one soft observation under the Jeffreys prior. */
  private static Map<String, Double> calibrated(Map<String, Double> probabilities) {
    double denominator = 1 + 0.5 * probabilities.size();
    Map<String, Double> result = new LinkedHashMap<>();
    probabilities.forEach((option, p) -> result.put(option, (p + 0.5) / denominator));
    return result;
  }
}
