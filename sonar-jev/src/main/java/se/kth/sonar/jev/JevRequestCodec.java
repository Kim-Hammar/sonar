package se.kth.sonar.jev;

import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;

/**
 * Encodes decision problems as Jev requests and decodes Jev's replies. A region choice becomes a
 * question with one option per region and a none option; a hypothesis choice becomes a ranking
 * question. Replies that do not name the offered options decode as unavailable.
 */
@Placeholder
public final class JevRequestCodec {
  /** Returns the request for {@code problem}. */
  public DecisionTransport.Request encode(DecisionProblem problem) {
    throw new NotImplemented("JevRequestCodec.encode");
  }

  /** Decodes the reply to a region choice. */
  public DecisionOutcome<RegionAssessment> decodeAssessment(
      DecisionProblem.RegionChoice problem, DecisionTransport.Reply reply) {
    throw new NotImplemented("JevRequestCodec.decodeAssessment");
  }

  /** Decodes the reply to a hypothesis choice. */
  public DecisionOutcome<HypothesisRanking> decodeRanking(
      DecisionProblem.HypothesisChoice problem, DecisionTransport.Reply reply) {
    throw new NotImplemented("JevRequestCodec.decodeRanking");
  }
}
