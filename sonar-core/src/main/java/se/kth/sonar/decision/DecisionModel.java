package se.kth.sonar.decision;

/**
 * Answers the questions the search asks a language model. An instance is used by one thread at a
 * time; {@link DecisionService} runs several in parallel.
 */
public interface DecisionModel {
  /** Judges which region of a partition holds the repair, and how likely it is that none does. */
  DecisionOutcome<RegionAssessment> assess(DecisionProblem.RegionChoice problem);

  /** Orders the candidates of a region, most promising first. */
  DecisionOutcome<HypothesisRanking> rank(DecisionProblem.HypothesisChoice problem);
}
