package se.kth.sonar.prior;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.HypothesisDependency;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.TransitionLedger;

/**
 * Weights a region by what the run has verified so far: candidates of the same class as its own are
 * exchangeable trials of one kind of edit, and their outcomes give the Jeffreys odds {@code
 * (repairs + 1/2) / (failures + 1/2)}. A trial counts only while the routers it changed still have
 * the configuration and data-plane dependency state it was tried against, and a near miss counts
 * for neither side, because it shows influence of one object rather than success or failure of the
 * class. A relation requirement is one class even before its region is expanded, so verified
 * failures immediately lower all exchangeable deferred regions. An untried class keeps the neutral
 * weight one.
 */
public final class ExperienceFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;

  private final TransitionLedger ledger;
  private final ContentCache<Map<ContentHash, Tally>> cachedTallies =
      new ContentCache<>(CACHE_CAPACITY);

  /** Creates a factor that learns from {@code ledger}. */
  public ExperienceFactor(TransitionLedger ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  @Override
  public String name() {
    return "experience";
  }

  @Override
  public double weight(Region region, RepairState state) {
    ContentHash key =
        ContentHash.builder().add(state.verification().digest()).add(ledger.size()).build();
    Map<ContentHash, Tally> tallies = cachedTallies.get(key, () -> tallies(state));
    Set<ContentHash> classes = classes(region);
    if (classes.isEmpty()) {
      return 1;
    }
    double weight = 0;
    for (ContentHash candidateClass : classes) {
      Tally tally = tallies.get(candidateClass);
      if (tally == null) {
        weight = Math.max(weight, 1);
      } else {
        weight = Math.max(weight, tally.odds());
      }
    }
    return weight;
  }

  /** Tallies outcomes tried against configuration and data-plane dependencies still current. */
  private Map<ContentHash, Tally> tallies(RepairState state) {
    Map<ContentHash, List<Boolean>> outcomes = new HashMap<>();
    for (TransitionLedger.Transition transition : ledger.all()) {
      if (transition.change() == Verdict.Change.MIXED
          || !transition
              .dependency()
              .equals(HypothesisDependency.of(transition.hypothesis(), state))) {
        continue;
      }
      for (ContentHash candidateClass : classes(transition.hypothesis())) {
        outcomes
            .computeIfAbsent(candidateClass, ignored -> new ArrayList<>())
            .add(transition.change() == Verdict.Change.IMPROVEMENT);
      }
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

  private static Set<ContentHash> classes(Region region) {
    Set<ContentHash> classes = new LinkedHashSet<>();
    if (region instanceof RelationRegion relation) {
      for (Requirement requirement : relation.instance().violated()) {
        if (requirement.signals()) {
          classes.add(restored(relation.instance().relation(), requirement));
        }
      }
    }
    if (region instanceof IncidenceRegion || region instanceof se.kth.sonar.search.MeshRegion) {
      Requirement missingPeer =
          new Requirement(
              Requirement.Strength.SOFT,
              new se.kth.sonar.relation.Constraint.Exists(
                  new se.kth.sonar.relation.AttributePath("remote", List.of())),
              java.util.Optional.empty());
      classes.add(restored("bgp-session", missingPeer));
    }
    if (region.hypotheses() instanceof Region.Hypotheses.Admitted admitted) {
      admitted.list().forEach(hypothesis -> classes.addAll(classes(hypothesis)));
    }
    return classes;
  }

  private static Set<ContentHash> classes(Hypothesis hypothesis) {
    Set<ContentHash> classes = new LinkedHashSet<>();
    for (Provenance.Restored restored : hypothesis.restores()) {
      classes.add(restored(restored.relation(), restored.requirement()));
    }
    if (classes.isEmpty()) {
      classes.add(hypothesis.signature());
    }
    return classes;
  }

  private static ContentHash restored(String relation, Requirement requirement) {
    return ContentHash.builder().add("restored").add(relation).add(requirement.text()).build();
  }
}
