package se.kth.sonar.decision;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import se.kth.sonar.execution.Budget;

/**
 * What asking the decision model produced.
 *
 * @param <T> the type of an answer
 */
public sealed interface DecisionOutcome<T> {
  /** Returns what asking consumed. */
  Usage usage();

  /** Returns every request sent to the provider and its reply, in order. */
  List<Exchange> exchanges();

  /**
   * The model answered.
   *
   * @param answer the answer
   * @param usage what asking consumed
   * @param exchanges the requests and replies, including retries
   * @param <T> the type of the answer
   */
  record Answered<T>(T answer, Usage usage, List<Exchange> exchanges)
      implements DecisionOutcome<T> {
    /** Checks the components. */
    public Answered {
      Objects.requireNonNull(answer, "answer");
      Objects.requireNonNull(usage, "usage");
      exchanges = List.copyOf(exchanges);
    }
  }

  /**
   * The model gave no usable answer, even after retries.
   *
   * @param reason why
   * @param usage what asking consumed
   * @param exchanges the requests and replies, including retries
   * @param <T> the type an answer would have had
   */
  record Unavailable<T>(String reason, Usage usage, List<Exchange> exchanges)
      implements DecisionOutcome<T> {
    /** Checks the components. */
    public Unavailable {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(usage, "usage");
      exchanges = List.copyOf(exchanges);
    }
  }

  /**
   * The question exceeds what the provider accepts, so it was not sent.
   *
   * @param size the size of the question in characters
   * @param limit the largest accepted size
   * @param <T> the type an answer would have had
   */
  record TooLarge<T>(int size, int limit) implements DecisionOutcome<T> {
    @Override
    public Usage usage() {
      return Usage.none();
    }

    @Override
    public List<Exchange> exchanges() {
      return List.of();
    }
  }

  /**
   * The budget ran out before the question was sent.
   *
   * @param resource the exhausted resource
   * @param <T> the type an answer would have had
   */
  record Stopped<T>(Budget.Resource resource) implements DecisionOutcome<T> {
    @Override
    public Usage usage() {
      return Usage.none();
    }

    @Override
    public List<Exchange> exchanges() {
      return List.of();
    }
  }

  /**
   * One request to the provider and its reply.
   *
   * @param request the request body as sent
   * @param reply the reply body as received
   * @param status the HTTP status code
   * @param latency the time from sending the request to receiving the reply
   * @param cached whether the reply came from the decision cache instead of the provider
   */
  record Exchange(String request, String reply, int status, Duration latency, boolean cached) {
    /** Checks the components. */
    public Exchange {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(reply, "reply");
      Objects.requireNonNull(latency, "latency");
    }
  }

  /**
   * What one question consumed.
   *
   * @param providerCalls the provider calls, including retries
   * @param inputTokens the tokens sent
   * @param outputTokens the tokens received
   */
  record Usage(int providerCalls, long inputTokens, long outputTokens) {
    /** Checks that the counts are not negative. */
    public Usage {
      if (providerCalls < 0 || inputTokens < 0 || outputTokens < 0) {
        throw new IllegalArgumentException("usage counts must not be negative");
      }
    }

    /** Returns zero usage. */
    public static Usage none() {
      return new Usage(0, 0, 0);
    }

    /** Returns the sum of both usages. */
    public Usage plus(Usage other) {
      return new Usage(
          providerCalls + other.providerCalls,
          inputTokens + other.inputTokens,
          outputTokens + other.outputTokens);
    }
  }
}
