package se.kth.sonar.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Chooses which verified improvements to adopt together, so that independent faults are repaired in
 * one step.
 *
 * <p>Candidates that improve the verdict without side effects are taken in order of how few
 * specifications they leave unresolved, then how small they are, then whether their values were
 * observed in the network. A candidate joins the adoption when its edits can be rebased over those
 * chosen before it without touching the same lines. The union is verified once and adopted if it
 * improves on the best single candidate without side effects; otherwise the best single candidate
 * is adopted, whose verification is already known.
 */
public final class Adoption {
  private final VerificationService verifier;

  /**
   * A candidate with its verification.
   *
   * @param hypothesis the candidate
   * @param verification the verification of its result
   */
  public record Verified(Hypothesis hypothesis, Verification verification) {
    /** Checks the components. */
    public Verified {
      Objects.requireNonNull(hypothesis, "hypothesis");
      Objects.requireNonNull(verification, "verification");
    }
  }

  /**
   * What was adopted.
   *
   * @param hypotheses the adopted candidates, in adoption order
   * @param snapshot the configurations after adoption
   * @param verification the verification of the new configurations
   */
  public record Adopted(List<Hypothesis> hypotheses, Snapshot snapshot, Verification verification) {
    /** Copies the candidates and checks the components. */
    public Adopted {
      hypotheses = List.copyOf(hypotheses);
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(verification, "verification");
    }
  }

  /** Creates an adoption step that verifies unions with {@code verifier}. */
  public Adoption(VerificationService verifier) {
    this.verifier = Objects.requireNonNull(verifier, "verifier");
  }

  /** Returns what to adopt in {@code state}, or nothing if no candidate improves it. */
  public Optional<Adopted> adopt(RepairState state, List<Verified> candidates) {
    List<Verified> improvements =
        candidates.stream()
            .filter(c -> c.verification().verdict().improvesOn(state.verification().verdict()))
            .sorted(
                Comparator.comparingInt((Verified c) -> c.verification().verdict().loss())
                    .thenComparingInt(c -> c.hypothesis().cost())
                    .thenComparing(c -> c.hypothesis().support())
                    .thenComparing(c -> c.hypothesis().id()))
            .toList();
    if (improvements.isEmpty()) {
      return Optional.empty();
    }
    Verified best = improvements.getFirst();
    Snapshot single = state.snapshot().patch(best.hypothesis().transaction()).after();
    Adopted fallback = new Adopted(List.of(best.hypothesis()), single, best.verification());
    if (improvements.size() == 1 || best.verification().verdict().solved()) {
      return Optional.of(fallback);
    }
    List<Hypothesis> chosen = new ArrayList<>(List.of(best.hypothesis()));
    Snapshot union = single;
    for (Verified other : improvements.subList(1, improvements.size())) {
      Snapshot.Delta soFar = delta(state.snapshot(), union);
      Optional<Transaction> rebased = other.hypothesis().transaction().rebaseOver(soFar);
      if (rebased.isEmpty()) {
        continue;
      }
      union = union.patch(rebased.get()).after();
      chosen.add(other.hypothesis());
    }
    SpecificationSet specifications = state.problem().specifications();
    if (chosen.size() == 1 || !affordable(union, specifications)) {
      return Optional.of(fallback);
    }
    Verification verification = verifier.verify(union, specifications);
    if (verification.verdict().improvesOn(best.verification().verdict())) {
      return Optional.of(new Adopted(chosen, union, verification));
    }
    return Optional.of(fallback);
  }

  private boolean affordable(Snapshot union, SpecificationSet specifications) {
    if (verifier.cached(union, specifications).isPresent()) {
      return true;
    }
    return RunScope.current()
        .map(scope -> scope.budget().tryConsume(Budget.Resource.VERIFICATIONS, 1))
        .orElse(true);
  }

  private static Snapshot.Delta delta(Snapshot before, Snapshot after) {
    TreeSet<RouterId> changed = new TreeSet<>();
    for (RouterId router : after.routers()) {
      if (!before.contains(router)
          || !before.text(router).digest().equals(after.text(router).digest())) {
        changed.add(router);
      }
    }
    return new Snapshot.Delta(before, after, Collections.unmodifiableNavigableSet(changed));
  }
}
