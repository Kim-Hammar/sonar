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
import java.util.function.Supplier;
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
import se.kth.sonar.search.MeshRegion;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.HypothesisDependency;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.TransitionLedger;
import se.kth.sonar.text.LineEdit;
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
  /** The most completions of a set-aside improvement verified at once. */
  private static final int COMPLETIONS = 2;

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
    private final List<Hypothesis> inert = new ArrayList<>();
    private final Set<ContentHash> verifierRejected = new LinkedHashSet<>();
    private final Map<String, List<Long>> timings = new LinkedHashMap<>();
    private Epoch epoch;
    private BeliefState belief;

    Run(RepairProblem problem, Budget budget, Cancellation cancellation) {
      this.problem = problem;
      this.budget = budget;
      this.cancellation = cancellation;
      this.epochs = components.epochs().with(new ExperienceFactor(ledger));
    }

    /** Runs {@code body}, recording its duration as one operation of {@code phase}. */
    private <T> T timed(String phase, Supplier<T> body) {
      long start = System.nanoTime();
      try {
        return body.get();
      } finally {
        timings.computeIfAbsent(phase, p -> new ArrayList<>()).add(System.nanoTime() - start);
      }
    }

    RepairResult execute() {
      if (!affordable(problem.initial())) {
        return failed("the budget allows no verification");
      }
      Verification baseline = verify(problem.initial());
      TraceSink.emit(new TraceEvent.Baseline(baseline));
      epoch = timed("search-space", () -> epochs.initial(problem, baseline));
      Distribution<Region.Id> prior = timed("prior", () -> epochs.prior(epoch));
      belief = timed("belief", () -> components.beliefs().initial(prior, epoch.state().epoch()));
      emitSpace();
      while (true) {
        if (cancellation.isCancelled()) {
          return result(new RepairResult.Stop.Cancelled());
        }
        SearchPolicy.Action action =
            timed(
                "policy",
                () -> components.policy().next(epoch.state(), epoch.space(), belief, budget));
        Optional<RepairResult.Stop> stop =
            switch (action) {
              case SearchPolicy.Action.Stop s -> Optional.of(s.reason());
              case SearchPolicy.Action.Assess a -> assess(a.partition());
              case SearchPolicy.Action.Expand e -> expand(e.region());
              case SearchPolicy.Action.Rank r -> rank(r.region());
              case SearchPolicy.Action.Verify v -> {
                emitStep(v.region(), v.hypotheses().size());
                yield verify(v.region(), v.hypotheses());
              }
            };
        if (stop.isPresent()) {
          return result(stop.get());
        }
      }
    }

    private Optional<RepairResult.Stop> assess(Partition partition) {
      DecisionProblem.RegionChoice question =
          timed(
              "decision-problem",
              () ->
                  components
                      .problems()
                      .regionChoice(
                          epoch.state(), epoch.space(), partition, ledger.triedIn(now())));
      DecisionOutcome<RegionAssessment> outcome =
          timed("decision-model", () -> components.decisions().assess(question));
      TraceSink.emit(new TraceEvent.Decided(question, outcome));
      belief =
          timed(
              "belief",
              () ->
                  switch (outcome) {
                    case DecisionOutcome.Answered<RegionAssessment> answered ->
                        components.beliefs().afterAssessment(belief, partition, answered.answer());
                    default -> components.beliefs().afterNeutralAssessment(belief, partition);
                  });
      return Optional.empty();
    }

    private Optional<RepairResult.Stop> expand(Region.Id id) {
      epoch = timed("candidates", () -> epochs.expand(epoch, id));
      if (epoch.space().region(id).isPresent()) {
        Distribution<Region.Id> prior = timed("prior", () -> epochs.prior(epoch));
        belief = timed("belief", () -> belief.withPrior(prior));
      } else {
        forget(id);
      }
      return Optional.empty();
    }

    private Optional<RepairResult.Stop> rank(Region.Id id) {
      Region region = epoch.space().region(id).orElseThrow();
      Region.Hypotheses.Admitted admitted = (Region.Hypotheses.Admitted) region.hypotheses();
      DecisionProblem.HypothesisChoice question =
          timed(
              "decision-problem",
              () ->
                  components
                      .problems()
                      .hypothesisChoice(epoch.state(), region, ledger.triedIn(now())));
      DecisionOutcome<HypothesisRanking> outcome =
          timed("decision-model", () -> components.decisions().rank(question));
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
      List<Adoption.Verified> improvements = new ArrayList<>();
      List<Adoption.Verified> mixedImprovements = new ArrayList<>();
      Set<ContentHash> tested = new LinkedHashSet<>();
      verifyAll(region, batch, snapshots, improvements, mixedImprovements, tested);
      completeSetAside(region, admitted, improvements, mixedImprovements, tested);
      if (improvements.isEmpty()) {
        for (Adoption.Verified mixed : mixedImprovements) {
          Optional<Adoption.Verified> part =
              timed("adoption", () -> components.adoption().part(epoch.state(), mixed));
          if (part.isPresent()) {
            improvements.add(part.get());
            break;
          }
        }
      }
      Optional<Adoption.Adopted> adoption =
          improvements.isEmpty()
              ? Optional.empty()
              : timed(
                  "adoption",
                  () -> components.adoption().adopt(epoch.state(), improvements, inert));
      if (adoption.isPresent()) {
        if (!adoption.get().verification().verdict().solved()) {
          int beforeReplacement = now();
          Optional<RepairResult.Stop> replacement = replaceCounterfactually(improvements, true);
          if (replacement.isPresent() || now() != beforeReplacement) {
            return replacement;
          }
        }
        adopt(adoption.get());
        return Optional.empty();
      }
      int beforeReplacement = now();
      Optional<RepairResult.Stop> replacement = replaceCounterfactually(mixedImprovements, false);
      if (replacement.isPresent() || now() != beforeReplacement) {
        return replacement;
      }
      double testedMass =
          admitted
              .ranking()
              .map(ranking -> ranking.mass(tested))
              .orElse((double) tested.size() / admitted.bound());
      belief = timed("belief", () -> components.beliefs().afterFailures(belief, id, testedMass));
      tested.addAll(known);
      trim(region, admitted.without(tested));
      belief =
          timed("belief", () -> components.beliefs().withNearMisses(belief, nearMissesByRegion()));
      return Optional.empty();
    }

    /** Verifies {@code batch} of {@code region} and records each outcome. */
    private void verifyAll(
        Region region,
        List<Hypothesis> batch,
        List<Snapshot> snapshots,
        List<Adoption.Verified> improvements,
        List<Adoption.Verified> mixedImprovements,
        Set<ContentHash> tested) {
      Region.Id id = region.id();
      List<VerificationService.Attempt> attempts =
          timed(
              "verification",
              () -> components.verifier().attemptAll(snapshots, problem.specifications()));
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
        if (verification.verdict().improvesOn(epoch.state().verification().verdict())) {
          improvements.add(new Adoption.Verified(hypothesis, verification));
        } else if (change == Verdict.Change.NO_EFFECT) {
          inert.add(hypothesis);
        } else if (change == Verdict.Change.MIXED) {
          if (verification.verdict().unresolved().size()
              < epoch.state().verification().verdict().unresolved().size()) {
            mixedImprovements.add(new Adoption.Verified(hypothesis, verification));
          }
          for (ProgramObject.Ref object : objectsOf(region)) {
            nearMisses.merge(object, 1, Integer::sum);
          }
        }
      }
    }

    /**
     * Verifies the completions of an improvement that adoption would set aside. When the best
     * improvement breaks a requirement that a worse rival keeps, the region's untried candidates
     * that make all its edits and more may restore the requirement while keeping its gain;
     * verifying them before adopting keeps a workaround from taking the place of the better value.
     */
    private void completeSetAside(
        Region region,
        Region.Hypotheses.Admitted admitted,
        List<Adoption.Verified> improvements,
        List<Adoption.Verified> mixedImprovements,
        Set<ContentHash> tested) {
      Optional<Adoption.Verified> setAside =
          timed(
              "adoption", () -> components.adoption().setAside(epoch.state(), improvements, inert));
      if (setAside.isEmpty()) {
        return;
      }
      List<LineEdit> edits = setAside.get().hypothesis().transaction().edits();
      List<Hypothesis> batch = new ArrayList<>();
      List<Snapshot> snapshots = new ArrayList<>();
      for (Hypothesis hypothesis : admitted.list()) {
        List<LineEdit> extended = hypothesis.transaction().edits();
        if (batch.size() == COMPLETIONS
            || tested.contains(hypothesis.id())
            || extended.size() <= edits.size()
            || !extended.containsAll(edits)
            || ledger.tried(hypothesis, dependency(hypothesis))) {
          continue;
        }
        Snapshot snapshot = epoch.state().snapshot().patch(hypothesis.transaction()).after();
        if (!affordable(snapshot)) {
          break;
        }
        batch.add(hypothesis);
        snapshots.add(snapshot);
      }
      if (!batch.isEmpty()) {
        verifyAll(region, batch, snapshots, improvements, mixedImprovements, tested);
      }
    }

    /**
     * Replaces one earlier greedy improvement when a mixed candidate becomes a clean improvement
     * without it. A replay the budget cannot verify ends the attempt and leaves the state as it is,
     * so that an improvement verified already can still be adopted.
     */
    private Optional<RepairResult.Stop> replaceCounterfactually(
        List<Adoption.Verified> candidates, boolean requireCandidateDominance) {
      if (candidates.isEmpty() || adopted.isEmpty()) {
        return Optional.empty();
      }
      List<Adoption.Verified> ordered =
          candidates.stream()
              .sorted(
                  java.util.Comparator.comparingInt(
                          (Adoption.Verified candidate) ->
                              candidate.verification().verdict().unresolved().size())
                      .thenComparing(candidate -> candidate.hypothesis().support())
                      .thenComparingInt(candidate -> candidate.hypothesis().cost())
                      .thenComparing(candidate -> candidate.hypothesis().id()))
              .limit(1)
              .toList();
      for (Adoption.Verified candidate : ordered) {
        for (CounterfactualReplacement.Alternative alternative :
            CounterfactualReplacement.alternatives(
                problem.initial(), adopted, candidate.hypothesis())) {
          if (!affordable(alternative.snapshot())) {
            return Optional.empty();
          }
          Verification verification = verify(alternative.snapshot());
          if (!verification.verdict().improvesOn(epoch.state().verification().verdict())
              || (requireCandidateDominance
                  && !verification.verdict().improvesOn(candidate.verification().verdict()))) {
            continue;
          }
          adopted.clear();
          adopted.addAll(alternative.history());
          advance(List.of(candidate.hypothesis()), alternative.snapshot(), verification);
          return Optional.empty();
        }
      }
      return Optional.empty();
    }

    private void adopt(Adoption.Adopted adoption) {
      adoption.hypotheses().forEach(hypothesis -> adopted.add(hypothesis.transaction()));
      advance(adoption.hypotheses(), adoption.snapshot(), adoption.verification());
    }

    private void advance(
        List<Hypothesis> hypotheses, Snapshot snapshot, Verification verification) {
      epoch = timed("search-space", () -> epochs.advance(epoch, snapshot, verification));
      TraceSink.emit(new TraceEvent.Adopted(now(), hypotheses, verification));
      nearMisses.clear();
      inert.clear();
      if (epoch.state().solved()) {
        return;
      }
      Distribution<Region.Id> prior = timed("prior", () -> epochs.prior(epoch));
      belief = timed("belief", () -> components.beliefs().initial(prior, now()));
      emitSpace();
    }

    /** Records a search step with the posterior of the ten most probable regions. */
    private void emitStep(Region.Id region, int candidates) {
      Distribution<Region.Id> posterior = belief.posterior();
      Map<Region.Id, Double> top = new LinkedHashMap<>();
      posterior.outcomes().stream()
          .sorted(
              java.util.Comparator.comparingDouble(posterior::probability)
                  .reversed()
                  .thenComparing(Region.Id::value))
          .limit(10)
          .forEach(id -> top.put(id, posterior.probability(id)));
      TraceSink.emit(new TraceEvent.Step(now(), region, candidates, top));
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
        case MeshRegion mesh -> mesh.mesh().objects();
        case TransferRegion transfer -> {
          Set<ProgramObject.Ref> objects = new LinkedHashSet<>();
          objects.add(transfer.transfer().policy());
          objects.addAll(transfer.transfer().blockers());
          transfer.transfer().attachment().ifPresent(item -> objects.add(item.neighbor()));
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
      return timed(
          "verification", () -> components.verifier().verify(snapshot, problem.specifications()));
    }

    private RepairResult result(RepairResult.Stop stop) {
      TraceSink.emit(new TraceEvent.Timings(timings));
      return new RepairResult(
          problem.name(),
          epoch.state().snapshot(),
          epoch.state().verification().verdict(),
          adopted,
          stop,
          budget.usage());
    }

    private RepairResult failed(String reason) {
      TraceSink.emit(new TraceEvent.Timings(timings));
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
