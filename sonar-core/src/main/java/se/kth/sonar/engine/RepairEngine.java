package se.kth.sonar.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.belief.BeliefModel;
import se.kth.sonar.belief.BeliefState;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.DecisionProblemFactory;
import se.kth.sonar.decision.DecisionService;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.Cancellation;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.policy.SearchPolicy;
import se.kth.sonar.prior.ExperienceFactor;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.HypothesisDependency;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.TransitionLedger;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceSink;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Repairs one network.
 *
 * <p>The run verifies the faulty network, builds its program and search space, and then repeats:
 * the policy picks an action; assessments and rankings update the belief; candidates are verified
 * in batches whose results are recorded in candidate order; and improvements are adopted, after
 * which the search space is refreshed and the belief starts again from the prior. Every step is
 * reported with {@link TraceSink#emit} from the calling thread. The run stops when the network is
 * repaired, the budget runs out, the run scope's cancellation token is set, or the search space is
 * empty.
 */
public final class RepairEngine implements Repairer {
  private final Components components;

  /**
   * The services a run uses.
   *
   * @param epochs builds epochs and their prior, before what the run itself learns
   * @param verifier verifies candidates
   * @param problems builds decision questions
   * @param decisions asks the decision model
   * @param beliefs updates beliefs
   * @param policy chooses actions
   * @param adoption adopts improvements
   */
  public record Components(
      EpochBuilder epochs,
      VerificationService verifier,
      DecisionProblemFactory problems,
      DecisionService decisions,
      BeliefModel beliefs,
      SearchPolicy policy,
      Adoption adoption) {

    /** Checks that every service is present. */
    public Components {
      Objects.requireNonNull(epochs, "epochs");
      Objects.requireNonNull(verifier, "verifier");
      Objects.requireNonNull(problems, "problems");
      Objects.requireNonNull(decisions, "decisions");
      Objects.requireNonNull(beliefs, "beliefs");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(adoption, "adoption");
    }
  }

  /** Creates an engine from its services. */
  public RepairEngine(Components components) {
    this.components = Objects.requireNonNull(components, "components");
  }

  @Override
  public RepairResult repair(RepairProblem problem) {
    Optional<RunScope> scope = RunScope.current();
    Budget budget = scope.map(RunScope::budget).orElseGet(Budget::unlimited);
    Cancellation cancellation = scope.map(RunScope::cancellation).orElseGet(Cancellation::new);
    return new Run(problem, budget, cancellation).execute();
  }

  /** One repair run: the loop and the state it carries between steps. */
  private final class Run {
    private final RepairProblem problem;
    private final Budget budget;
    private final Cancellation cancellation;
    private final TransitionLedger ledger = new TransitionLedger();
    private final EpochBuilder epochs;
    private final List<Transaction> adopted = new ArrayList<>();
    private final Map<ProgramObject.Ref, Integer> nearMisses = new HashMap<>();
    private final Set<ContentHash> verifierRejected = new LinkedHashSet<>();
    private Epoch epoch;
    private BeliefState belief;

    Run(RepairProblem problem, Budget budget, Cancellation cancellation) {
      this.problem = problem;
      this.budget = budget;
      this.cancellation = cancellation;
      this.epochs = components.epochs().with(new ExperienceFactor(ledger));
    }

    RepairResult execute() {
      if (!affordable(problem.initial())) {
        return failed("the budget allows no verification");
      }
      Verification baseline = verify(problem.initial());
      TraceSink.emit(new TraceEvent.Baseline(baseline));
      epoch = epochs.initial(problem, baseline);
      belief = components.beliefs().initial(epochs.prior(epoch), epoch.state().epoch());
      emitSpace();
      while (true) {
        if (cancellation.isCancelled()) {
          return result(new RepairResult.Stop.Cancelled());
        }
        SearchPolicy.Action action =
            components.policy().next(epoch.state(), epoch.space(), belief, budget);
        Optional<RepairResult.Stop> stop =
            switch (action) {
              case SearchPolicy.Action.Stop s -> Optional.of(s.reason());
              case SearchPolicy.Action.Assess a -> assess(a.partition());
              case SearchPolicy.Action.Expand e -> expand(e.region());
              case SearchPolicy.Action.Rank r -> rank(r.region());
              case SearchPolicy.Action.Verify v -> verify(v.region(), v.hypotheses());
            };
        if (stop.isPresent()) {
          return result(stop.get());
        }
      }
    }

    private Optional<RepairResult.Stop> assess(Partition partition) {
      DecisionProblem.RegionChoice question =
          components
              .problems()
              .regionChoice(epoch.state(), epoch.space(), partition, ledger.triedIn(now()));
      DecisionOutcome<RegionAssessment> outcome = components.decisions().assess(question);
      TraceSink.emit(new TraceEvent.Decided(question, outcome));
      belief =
          switch (outcome) {
            case DecisionOutcome.Answered<RegionAssessment> answered ->
                components.beliefs().afterAssessment(belief, partition, answered.answer());
            default -> components.beliefs().afterNeutralAssessment(belief, partition);
          };
      return Optional.empty();
    }

    private Optional<RepairResult.Stop> expand(Region.Id id) {
      epoch = epochs.expand(epoch, id);
      if (epoch.space().region(id).isPresent()) {
        belief = belief.withPrior(epochs.prior(epoch));
      } else {
        forget(id);
      }
      return Optional.empty();
    }

    private Optional<RepairResult.Stop> rank(Region.Id id) {
      Region region = epoch.space().region(id).orElseThrow();
      Region.Hypotheses.Admitted admitted = (Region.Hypotheses.Admitted) region.hypotheses();
      DecisionProblem.HypothesisChoice question =
          components.problems().hypothesisChoice(epoch.state(), region, ledger.triedIn(now()));
      DecisionOutcome<HypothesisRanking> outcome = components.decisions().rank(question);
      TraceSink.emit(new TraceEvent.Decided(question, outcome));
      Distribution<ContentHash> probabilities =
          outcome instanceof DecisionOutcome.Answered<HypothesisRanking> answered
              ? answered.answer().probabilities()
              : Distribution.uniform(admitted.list().stream().map(Hypothesis::id).toList());
      epoch = epochs.replace(epoch, region.withHypotheses(admitted.rankedBy(probabilities)));
      return Optional.empty();
    }

    private Optional<RepairResult.Stop> verify(Region.Id id, List<ContentHash> ids) {
      Region region = epoch.space().region(id).orElseThrow();
      Region.Hypotheses.Admitted admitted = (Region.Hypotheses.Admitted) region.hypotheses();
      if (admitted.list().isEmpty()) {
        remove(id);
        return Optional.empty();
      }
      Set<ContentHash> wanted = new LinkedHashSet<>(ids);
      List<Hypothesis> batch = new ArrayList<>();
      List<Snapshot> snapshots = new ArrayList<>();
      Set<ContentHash> known = new LinkedHashSet<>();
      for (Hypothesis hypothesis : admitted.list()) {
        if (!wanted.contains(hypothesis.id())) {
          continue;
        }
        ContentHash dependency = dependency(hypothesis);
        if (ledger.tried(hypothesis, dependency)
            || verifierRejected.contains(attemptKey(hypothesis, dependency))) {
          known.add(hypothesis.id());
          continue;
        }
        Snapshot snapshot = epoch.state().snapshot().patch(hypothesis.transaction()).after();
        if (!affordable(snapshot)) {
          break;
        }
        batch.add(hypothesis);
        snapshots.add(snapshot);
      }
      if (batch.isEmpty() && !known.isEmpty()) {
        trim(region, admitted.without(known));
        return Optional.empty();
      }
      if (batch.isEmpty()) {
        return Optional.of(new RepairResult.Stop.BudgetExhausted(Budget.Resource.VERIFICATIONS));
      }
      List<VerificationService.Attempt> attempts =
          components.verifier().attemptAll(snapshots, problem.specifications());
      List<Adoption.Verified> improvements = new ArrayList<>();
      Set<ContentHash> tested = new LinkedHashSet<>();
      for (int at = 0; at < batch.size(); at++) {
        Hypothesis hypothesis = batch.get(at);
        VerificationService.Attempt attempt = attempts.get(at);
        if (attempt instanceof VerificationService.Attempt.Rejected rejected) {
          verifierRejected.add(attemptKey(hypothesis, dependency(hypothesis)));
          TraceSink.emit(new TraceEvent.Rejected(id, hypothesis, rejected.reason()));
          tested.add(hypothesis.id());
          continue;
        }
        Verification verification = ((VerificationService.Attempt.Success) attempt).verification();
        Verdict.Change change =
            epoch.state().verification().verdict().changeTo(verification.verdict());
        ledger.add(
            new TransitionLedger.Transition(
                now(), id, hypothesis, verification, change, dependency(hypothesis)));
        TraceSink.emit(new TraceEvent.Tried(id, hypothesis, verification, change));
        tested.add(hypothesis.id());
        if (change == Verdict.Change.IMPROVEMENT) {
          improvements.add(new Adoption.Verified(hypothesis, verification));
        } else if (change == Verdict.Change.MIXED) {
          for (ProgramObject.Ref object : objectsOf(region)) {
            nearMisses.merge(object, 1, Integer::sum);
          }
        }
      }
      Optional<Adoption.Adopted> adoption =
          improvements.isEmpty()
              ? Optional.empty()
              : components.adoption().adopt(epoch.state(), improvements);
      if (adoption.isPresent()) {
        adopt(adoption.get());
        return Optional.empty();
      }
      double testedMass =
          admitted
              .ranking()
              .map(ranking -> ranking.mass(tested))
              .orElse((double) tested.size() / admitted.bound());
      belief = components.beliefs().afterFailures(belief, id, testedMass);
      tested.addAll(known);
      trim(region, admitted.without(tested));
      belief = components.beliefs().withNearMisses(belief, nearMissesByRegion());
      return Optional.empty();
    }

    private void adopt(Adoption.Adopted adoption) {
      epoch = epochs.advance(epoch, adoption.snapshot(), adoption.verification());
      adoption.hypotheses().forEach(hypothesis -> adopted.add(hypothesis.transaction()));
      TraceSink.emit(new TraceEvent.Adopted(now(), adoption.hypotheses(), adoption.verification()));
      nearMisses.clear();
      if (epoch.state().solved()) {
        return;
      }
      belief = components.beliefs().initial(epochs.prior(epoch), now());
      emitSpace();
    }

    private void emitSpace() {
      TraceSink.emit(new TraceEvent.SpaceBuilt(now(), epoch.space(), Optional.of(belief.prior())));
    }

    private int now() {
      return epoch.state().epoch();
    }

    /** Keeps the candidates {@code remaining} of {@code region}, or drops the region if none. */
    private void trim(Region region, Region.Hypotheses.Admitted remaining) {
      if (remaining.bound() == 0) {
        remove(region.id());
      } else {
        epoch = epochs.replace(epoch, region.withHypotheses(remaining));
      }
    }

    private void remove(Region.Id id) {
      epoch = epochs.without(epoch, List.of(id));
      forget(id);
    }

    /** Drops {@code id} from the belief, unless nothing is left to believe in. */
    private void forget(Region.Id id) {
      if (!epoch.space().isEmpty()) {
        belief = belief.without(List.of(id));
      }
    }

    private Map<Region.Id, Integer> nearMissesByRegion() {
      Map<Region.Id, Integer> counts = new LinkedHashMap<>();
      if (nearMisses.isEmpty()) {
        return counts;
      }
      for (Region region : epoch.space().regions()) {
        int count = 0;
        for (ProgramObject.Ref object : objectsOf(region)) {
          count = Math.max(count, nearMisses.getOrDefault(object, 0));
        }
        if (count > 0) {
          counts.put(region.id(), count);
        }
      }
      return counts;
    }

    private static Set<ProgramObject.Ref> objectsOf(Region region) {
      return switch (region) {
        case SiteRegion site -> Set.of(site.site());
        case RelationRegion relation -> Set.copyOf(relation.instance().roles().values());
        case IncidenceRegion incidence -> incidence.incidence().objects();
        case TransferRegion transfer -> {
          Set<ProgramObject.Ref> objects = new LinkedHashSet<>();
          objects.add(transfer.transfer().policy());
          objects.addAll(transfer.transfer().blockers());
          yield Set.copyOf(objects);
        }
      };
    }

    private ContentHash dependency(Hypothesis hypothesis) {
      return HypothesisDependency.of(hypothesis, epoch.state());
    }

    private static ContentHash attemptKey(Hypothesis hypothesis, ContentHash dependency) {
      return ContentHash.builder().add(hypothesis.id()).add(dependency).build();
    }

    /** Charges one verification for {@code snapshot} unless its result is known already. */
    private boolean affordable(Snapshot snapshot) {
      return components.verifier().cached(snapshot, problem.specifications()).isPresent()
          || budget.tryConsume(Budget.Resource.VERIFICATIONS, 1);
    }

    private Verification verify(Snapshot snapshot) {
      return components.verifier().verify(snapshot, problem.specifications());
    }

    private RepairResult result(RepairResult.Stop stop) {
      return new RepairResult(
          problem.name(),
          epoch.state().snapshot(),
          epoch.state().verification().verdict(),
          adopted,
          stop,
          budget.usage());
    }

    private RepairResult failed(String reason) {
      return new RepairResult(
          problem.name(),
          problem.initial(),
          new Verdict(problem.specifications().broken(), List.of(), List.of()),
          List.of(),
          new RepairResult.Stop.Failed(reason),
          budget.usage());
    }
  }
}
