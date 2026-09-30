package se.kth.sonar.prior;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.search.Region;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.TransitionLedger;
import se.kth.sonar.text.RouterId;

/**
 * Weights a region by what the run has verified so far: candidates of the same class as its own are
 * exchangeable trials of one kind of edit, and their outcomes give the Jeffreys odds {@code
 * (repairs + 1/2) / (failures + 1/2)}. A trial counts only while the routers it changed still have
 * the text it was tried against, and a near miss counts for neither side, because it shows
 * influence of one object rather than success or failure of the class. Regions of an untried class
 * keep the neutral weight one, and no region weighs less than that.
 */
public final class ExperienceFactor implements PriorFactor {
  private final TransitionLedger ledger;

  /** Creates a factor that learns from {@code ledger}. */
  public ExperienceFactor(TransitionLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  @Override
  public double weight(Region region, RepairState state) {
    if (!(region.hypotheses() instanceof Region.Hypotheses.Admitted admitted)) {
      return 1;
    }
    Map<ContentHash, Tally> tallies = tallies(state.snapshot());
    double weight = 1;
    Set<ContentHash> seen = new HashSet<>();
    for (Hypothesis hypothesis : admitted.list()) {
      ContentHash signature = hypothesis.signature();
      Tally tally = tallies.get(signature);
      if (tally != null && seen.add(signature)) {
        weight = Math.max(weight, tally.odds());
      }
    }
    return weight;
  }

  /** Tallies the outcomes of every class of candidate tried against text that is still current. */
  private Map<ContentHash, Tally> tallies(Snapshot current) {
    Map<ContentHash, List<Boolean>> outcomes = new HashMap<>();
    for (TransitionLedger.Transition transition : ledger.all()) {
      if (transition.change() == Verdict.Change.MIXED || !current(transition, current)) {
        continue;
      }
      outcomes
          .computeIfAbsent(transition.hypothesis().signature(), s -> new ArrayList<>())
          .add(transition.change() == Verdict.Change.IMPROVEMENT);
    }
    Map<ContentHash, Tally> tallies = new HashMap<>();
    outcomes.forEach(
        (signature, results) -> {
          Tally tally = Tally.empty();
          for (boolean success : results) {
            tally = tally.plus(success);
          }
          tallies.put(signature, tally);
        });
    return tallies;
  }

  private static boolean current(TransitionLedger.Transition transition, Snapshot current) {
    for (Map.Entry<RouterId, ContentHash> base :
        transition.hypothesis().transaction().base().entrySet()) {
      if (!current.contains(base.getKey())
          || !current.text(base.getKey()).digest().equals(base.getValue())) {
        return false;
      }
    }
    return true;
  }
}
