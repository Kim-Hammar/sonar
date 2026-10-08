package se.kth.sonar.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Chooses which verified improvements to adopt together, so that independent faults are repaired in
 * one step.
 *
 * <p>Candidates that improve the verdict without side effects are taken in order of how few
 * specifications they leave unresolved, then the strength of their program-analysis evidence, then
 * how small they are. A candidate that makes a satisfied relation instance violate a requirement is
 * a workaround, or an edit left incomplete, when another improvement is an alternative for the same
 * place and keeps every requirement: of two such candidates the one that breaks a requirement is
 * set aside, unless it solves the problem. Between unrelated candidates the unresolved
 * specifications decide, since a repair can expose what another fault was hiding. A candidate joins
 * the adoption when it breaks no requirement and its edits can be rebased over those chosen before
 * it without touching the same lines, unless it extends one of them and was verified to resolve
 * nothing more. An improvement that extends a candidate verified to change nothing owes its effect
 * to the rest of its edits, so the rest is verified alone and, if it does as well, considered in
 * its place. The union is verified once and adopted if it improves on the best single candidate
 * without side effects; otherwise the best single candidate is adopted, whose verification is
 * already known.
 */
public final class Adoption {
  private final VerificationService verifier;
  private final ProgramService programs;

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

  /**
   * Creates an adoption step that verifies unions with {@code verifier} and reads the requirements
   * a candidate breaks from the programs of {@code programs}.
   */
  public Adoption(VerificationService verifier, ProgramService programs) {
    this.verifier = Objects.requireNonNull(verifier, "verifier");
    this.programs = Objects.requireNonNull(programs, "programs");
  }

  /** Returns what to adopt in {@code state}, or nothing if no candidate improves it. */
  public Optional<Adopted> adopt(RepairState state, List<Verified> verified) {
    return adopt(state, verified, List.of());
  }

  /**
   * Returns the improvement with the least loss when {@link #adopt} would set it aside: it breaks a
   * requirement satisfied in {@code state} and an improvement that keeps the requirement rivals it
   * for the same place.
   */
  public Optional<Verified> setAside(
      RepairState state, List<Verified> verified, List<Hypothesis> inert) {
    Set<ContentHash> satisfied = new HashSet<>();
    for (RelationInstance instance : state.program().instances()) {
      if (instance.satisfied()) {
        satisfied.add(instance.id());
      }
    }
    List<Verified> improving =
        withoutInertEdits(state, verified, inert).stream()
            .filter(c -> c.verification().verdict().improvesOn(state.verification().verdict()))
            .toList();
    Optional<Verified> best =
        improving.stream()
            .min(Comparator.comparingInt((Verified c) -> c.verification().verdict().loss()));
    if (best.isEmpty()
        || best.get().verification().verdict().solved()
        || !breaksARequirement(state, best.get(), satisfied)) {
      return Optional.empty();
    }
    boolean replaced =
        improving.stream()
            .filter(other -> !other.hypothesis().id().equals(best.get().hypothesis().id()))
            .filter(other -> !breaksARequirement(state, other, satisfied))
            .anyMatch(other -> rivals(best.get(), other));
    return replaced ? best : Optional.empty();
  }

  /**
   * Returns what to adopt in {@code state}, or nothing if no candidate improves it, given the
   * candidates {@code inert} that were verified to change nothing in this state.
   */
  public Optional<Adopted> adopt(
      RepairState state, List<Verified> verified, List<Hypothesis> inert) {
    List<Verified> candidates = withoutInertEdits(state, verified, inert);
    Set<ContentHash> satisfied = new HashSet<>();
    for (RelationInstance instance : state.program().instances()) {
      if (instance.satisfied()) {
        satisfied.add(instance.id());
      }
    }
    List<Verified> improving =
        candidates.stream()
            .filter(c -> c.verification().verdict().improvesOn(state.verification().verdict()))
            .toList();
    Set<ContentHash> breaking = new HashSet<>();
    for (Verified candidate : improving) {
      if (!candidate.verification().verdict().solved()
          && breaksARequirement(state, candidate, satisfied)) {
        breaking.add(candidate.hypothesis().id());
      }
    }
    List<Verified> improvements =
        improving.stream()
            .filter(
                c ->
                    !breaking.contains(c.hypothesis().id())
                        || improving.stream()
                            .filter(other -> !breaking.contains(other.hypothesis().id()))
                            .noneMatch(other -> rivals(c, other)))
            .sorted(
                Comparator.comparingInt((Verified c) -> c.verification().verdict().loss())
                    .thenComparing(c -> c.hypothesis().support())
                    .thenComparingInt(c -> c.hypothesis().cost()))
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
    List<Verified> taken = new ArrayList<>(List.of(best));
    Snapshot union = single;
    for (Verified other : improvements.subList(1, improvements.size())) {
      if (breaking.contains(other.hypothesis().id())
          || taken.stream().anyMatch(smaller -> extendsWithoutGain(other, smaller))) {
        continue;
      }
      Snapshot.Delta soFar = delta(state.snapshot(), union);
      Optional<Transaction> rebased = other.hypothesis().transaction().rebaseOver(soFar);
      if (rebased.isEmpty()) {
        continue;
      }
      union = union.patch(rebased.get()).after();
      chosen.add(other.hypothesis());
      taken.add(other);
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

  /**
   * Returns the candidates with every improvement that extends an inert candidate reduced to the
   * rest of its edits, where the rest alone verifies as at least as good. The budget decides
   * whether the rest can be verified; if it cannot, the improvement stays as it is.
   */
  private List<Verified> withoutInertEdits(
      RepairState state, List<Verified> candidates, List<Hypothesis> unchanging) {
    Verdict before = state.verification().verdict();
    List<Hypothesis> inert = new ArrayList<>(unchanging);
    candidates.stream()
        .filter(c -> before.changeTo(c.verification().verdict()) == Verdict.Change.NO_EFFECT)
        .forEach(c -> inert.add(c.hypothesis()));
    inert.sort(Comparator.comparingInt((Hypothesis h) -> h.operators().size()).reversed());
    List<Verified> result = new ArrayList<>();
    for (Verified candidate : candidates) {
      Verdict verdict = candidate.verification().verdict();
      Optional<Verified> reduced =
          verdict.improvesOn(before)
              ? inert.stream()
                  .map(without -> rest(state, candidate, without))
                  .flatMap(Optional::stream)
                  .findFirst()
              : Optional.empty();
      result.add(
          reduced
              .filter(rest -> rest.verification().verdict().improvesOn(before))
              .filter(rest -> rest.verification().verdict().loss() <= verdict.loss())
              .orElse(candidate));
    }
    return result;
  }

  /**
   * Returns a part of {@code candidate} that improves on {@code state}, for a candidate that
   * resolves every broken specification but breaks an intact one. Such a candidate is a repair with
   * something added, so each of its edits alone is verified, and then the candidate without each of
   * its edits, until one improves on the state. The budget bounds the verifications.
   */
  public Optional<Verified> part(RepairState state, Verified candidate) {
    List<EditOperator> operators = candidate.hypothesis().operators();
    if (operators.size() < 2 || !candidate.verification().verdict().unresolved().isEmpty()) {
      return Optional.empty();
    }
    List<List<EditOperator>> parts = new ArrayList<>();
    operators.forEach(operator -> parts.add(List.of(operator)));
    if (operators.size() > 2) {
      for (int dropped = 0; dropped < operators.size(); dropped++) {
        List<EditOperator> rest = new ArrayList<>(operators);
        rest.remove(dropped);
        parts.add(rest);
      }
    }
    Verdict before = state.verification().verdict();
    for (List<EditOperator> part : parts) {
      Optional<Verified> verified = verified(state, part);
      if (verified.filter(v -> v.verification().verdict().improvesOn(before)).isPresent()) {
        return verified;
      }
    }
    return Optional.empty();
  }

  /** Verifies the candidate made of {@code operators}, if it applies and can be afforded. */
  private Optional<Verified> verified(RepairState state, List<EditOperator> operators) {
    try {
      Hypothesis hypothesis = Hypothesis.of(state.snapshot(), operators);
      Snapshot after = state.snapshot().patch(hypothesis.transaction()).after();
      SpecificationSet specifications = state.problem().specifications();
      return affordable(after, specifications)
          ? Optional.of(new Verified(hypothesis, verifier.verify(after, specifications)))
          : Optional.empty();
    } catch (IllegalArgumentException | IllegalStateException inapplicable) {
      return Optional.empty();
    }
  }

  /**
   * Verifies {@code candidate} without the edits of {@code inert}, if those are part of it and the
   * rest leaves no relation instance violated that the whole kept: an edit that changes nothing on
   * its own can still complete another, as a reference completes a rename.
   */
  private Optional<Verified> rest(RepairState state, Verified candidate, Hypothesis inert) {
    Set<LineEdit> without = new HashSet<>();
    inert.operators().forEach(operator -> without.add(operator.lower()));
    List<EditOperator> operators = candidate.hypothesis().operators();
    List<EditOperator> rest =
        operators.stream().filter(operator -> !without.contains(operator.lower())).toList();
    if (rest.isEmpty() || operators.size() - rest.size() != without.size()) {
      return Optional.empty();
    }
    try {
      Set<ContentHash> kept = violated(state, candidate.hypothesis());
      if (!kept.containsAll(violated(state, Hypothesis.of(state.snapshot(), rest)))) {
        return Optional.empty();
      }
    } catch (IllegalArgumentException | IllegalStateException inapplicable) {
      return Optional.empty();
    }
    return verified(state, rest);
  }

  /** Returns the relation instances that the program violates after {@code hypothesis}. */
  private Set<ContentHash> violated(RepairState state, Hypothesis hypothesis) {
    Snapshot after = state.snapshot().patch(hypothesis.transaction()).after();
    Set<ContentHash> violated = new HashSet<>();
    programs
        .model(after, state.problem().topology(), Optional.of(state.program()))
        .violated()
        .forEach(instance -> violated.add(instance.id()));
    return violated;
  }

  /**
   * Returns whether {@code larger} makes every edit of {@code smaller} and more, and was verified
   * to resolve nothing that {@code smaller} leaves unresolved.
   */
  private static boolean extendsWithoutGain(Verified larger, Verified smaller) {
    return larger.verification().verdict().loss() >= smaller.verification().verdict().loss()
        && larger
            .hypothesis()
            .transaction()
            .edits()
            .containsAll(smaller.hypothesis().transaction().edits());
  }

  /**
   * Returns whether two candidates are alternatives for the same place: they make an edit in
   * common, rewrite or delete a line in common, or insert after the same line.
   */
  private static boolean rivals(Verified one, Verified other) {
    Set<Object> places = new HashSet<>();
    one.hypothesis().transaction().edits().forEach(edit -> places.addAll(places(edit)));
    return other.hypothesis().transaction().edits().stream()
        .anyMatch(edit -> places(edit).stream().anyMatch(places::contains));
  }

  private static List<Object> places(LineEdit edit) {
    List<Object> places = new ArrayList<>(List.of(edit));
    switch (edit) {
      case LineEdit.Delete delete -> places.addAll(lines(delete.span()));
      case LineEdit.Replace replace -> places.addAll(lines(replace.span()));
      case LineEdit.InsertAfter insert ->
          places.add(List.of(insert.router(), "after", insert.line()));
      case LineEdit.Append append -> {}
    }
    return places;
  }

  private static List<Object> lines(SourceSpan span) {
    List<Object> lines = new ArrayList<>();
    for (int line = span.first(); line <= span.last(); line++) {
      lines.add(List.of(span.router(), line));
    }
    return lines;
  }

  /**
   * Returns whether the candidate makes one of the {@code satisfied} relation instances violate a
   * requirement, be it a soft one or a convention; a hard one is never admitted. An instance that
   * was violated already, or that the candidate creates, is not one the program kept: a repair of
   * one of its requirements can expose another.
   */
  private boolean breaksARequirement(
      RepairState state, Verified candidate, Set<ContentHash> satisfied) {
    Snapshot after = state.snapshot().patch(candidate.hypothesis().transaction()).after();
    ProgramModel program =
        programs.model(after, state.problem().topology(), Optional.of(state.program()));
    return program.violated().stream().anyMatch(instance -> satisfied.contains(instance.id()));
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
