package se.kth.sonar.jev;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import se.kth.sonar.decision.DecisionModel;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;

/**
 * The Jev decision model: encodes each question, sends it, retries rate-limited, failed and
 * unreachable requests a bounded number of times with growing pauses, and reports what every
 * attempt consumed. A question larger than the payload limit is not sent.
 */
public final class JevDecisionModel implements DecisionModel {
  private static final int OK = 200;
  private static final int TOO_MANY_REQUESTS = 429;
  private static final int SERVER_ERRORS = 500;
  private static final int MAX_ATTEMPTS = 4;
  private static final Duration FIRST_PAUSE = Duration.ofSeconds(1);
  private static final Duration LONGEST_PAUSE = Duration.ofSeconds(8);
  private static final int DEFAULT_PAYLOAD_LIMIT = 60_000;

  private final DecisionTransport transport;
  private final JevRequestCodec codec;
  private final int payloadLimit;

  /** Creates a model that sends requests of at most the default size through {@code transport}. */
  public JevDecisionModel(DecisionTransport transport, JevRequestCodec codec) {
    this(transport, codec, DEFAULT_PAYLOAD_LIMIT);
  }

  /** Creates a model that sends requests of at most {@code payloadLimit} characters. */
  public JevDecisionModel(DecisionTransport transport, JevRequestCodec codec, int payloadLimit) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.codec = Objects.requireNonNull(codec, "codec");
    if (payloadLimit < 1) {
      throw new IllegalArgumentException("payload limit must be at least 1: " + payloadLimit);
    }
    this.payloadLimit = payloadLimit;
  }

  @Override
  public DecisionOutcome<RegionAssessment> assess(DecisionProblem.RegionChoice problem) {
    Attempted attempted = attempt(problem);
    if (attempted.reply().isPresent()) {
      return codec.decodeAssessment(
          problem, attempted.reply().get(), attempted.usage(), attempted.exchanges());
    }
    return attempted.failure();
  }

  @Override
  public DecisionOutcome<HypothesisRanking> rank(DecisionProblem.HypothesisChoice problem) {
    Attempted attempted = attempt(problem);
    if (attempted.reply().isPresent()) {
      return codec.decodeRanking(
          problem, attempted.reply().get(), attempted.usage(), attempted.exchanges());
    }
    return attempted.failure();
  }

  /** The successful reply, or why there is none, with what every attempt consumed. */
  private record Attempted(
      Optional<DecisionTransport.Reply> reply,
      DecisionOutcome.Usage usage,
      List<DecisionOutcome.Exchange> exchanges,
      Optional<String> reason,
      boolean tooLarge,
      int size,
      int limit) {

    <T> DecisionOutcome<T> failure() {
      if (tooLarge) {
        return new DecisionOutcome.TooLarge<>(size, limit);
      }
      return new DecisionOutcome.Unavailable<>(reason.orElse("no reply"), usage, exchanges);
    }
  }

  private Attempted attempt(DecisionProblem problem) {
    DecisionTransport.Request request = codec.encode(problem);
    if (request.body().length() > payloadLimit) {
      return new Attempted(
          Optional.empty(),
          DecisionOutcome.Usage.none(),
          List.of(),
          Optional.empty(),
          true,
          request.body().length(),
          payloadLimit);
    }
    DecisionOutcome.Usage usage = DecisionOutcome.Usage.none();
    List<DecisionOutcome.Exchange> exchanges = new ArrayList<>();
    String reason = "no reply";
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      if (attempt > 0) {
        Duration pause = FIRST_PAUSE.multipliedBy(1L << (attempt - 1));
        try {
          Thread.sleep(pause.compareTo(LONGEST_PAUSE) > 0 ? LONGEST_PAUSE : pause);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          reason = "interrupted while waiting to retry";
          break;
        }
      }
      Instant started = Instant.now();
      DecisionTransport.Reply reply;
      try {
        reply = transport.send(request);
      } catch (IOException e) {
        reason = "the request failed: " + e.getMessage();
        usage = usage.plus(new DecisionOutcome.Usage(1, 0, 0));
        continue;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        reason = "interrupted while waiting for a reply";
        break;
      }
      exchanges.add(
          new DecisionOutcome.Exchange(
              request.body(),
              reply.body(),
              reply.status(),
              Duration.between(started, Instant.now()),
              reply.cached()));
      if (!reply.cached()) {
        usage = usage.plus(JevRequestCodec.usageOf(reply));
      }
      if (reply.status() == OK) {
        return new Attempted(
            Optional.of(reply),
            usage,
            List.copyOf(exchanges),
            Optional.empty(),
            false,
            0,
            payloadLimit);
      }
      reason = "the provider answered " + reply.status();
      if (reply.status() != TOO_MANY_REQUESTS && reply.status() < SERVER_ERRORS) {
        break;
      }
    }
    return new Attempted(
        Optional.empty(),
        usage,
        List.copyOf(exchanges),
        Optional.of(reason),
        false,
        0,
        payloadLimit);
  }
}
