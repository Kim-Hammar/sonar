package se.kth.sonar.jev;

import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.decision.DecisionModel;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;

/**
 * The Jev decision model: encodes each question, sends it, retries replies that fail or cannot be
 * decoded a bounded number of times, and reports what every attempt consumed.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class JevDecisionModel implements DecisionModel {
  private final DecisionTransport transport;
  private final JevRequestCodec codec;

  /** Creates a model that sends requests through {@code transport}. */
  public JevDecisionModel(DecisionTransport transport, JevRequestCodec codec) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  public DecisionOutcome<RegionAssessment> assess(DecisionProblem.RegionChoice problem) {
    throw new NotImplemented("JevDecisionModel.assess");
  }

  @Override
  public DecisionOutcome<HypothesisRanking> rank(DecisionProblem.HypothesisChoice problem) {
    throw new NotImplemented("JevDecisionModel.rank");
  }
}
