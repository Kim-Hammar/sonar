package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.text.RouterId;

/**
 * A repair under construction: the operators applied so far, in order, and the snapshot and program
 * they produce. Each operator is applied to the snapshot the earlier ones produced, so its
 * statement refers to that text. Drafts are immutable; {@link #with} returns the extended draft.
 */
public final class Draft {
  private final ProgramService programs;
  private final ProgramModel base;
  private final ProgramModel program;
  private final List<EditOperator> operators;

  private Draft(
      ProgramService programs,
      ProgramModel base,
      ProgramModel program,
      List<EditOperator> operators) {
    this.programs = programs;
    this.base = base;
    this.program = program;
    this.operators = operators;
  }

  /** Starts an empty draft over {@code program}. */
  public static Draft of(ProgramService programs, ProgramModel program) {
    Objects.requireNonNull(programs, "programs");
    Objects.requireNonNull(program, "program");
    return new Draft(programs, program, program, List.of());
  }

  /**
   * Returns the draft extended by {@code operator}.
   *
   * @throws IllegalArgumentException if the operator's edit does not apply to the current text
   */
  public Draft with(EditOperator operator) {
    Snapshot current = program.snapshot();
    Snapshot next = current.patch(Transaction.of(current, List.of(operator.lower()))).after();
    List<EditOperator> extended = new ArrayList<>(operators);
    extended.add(operator);
    ProgramModel model = programs.model(next, program.topology(), Optional.of(program));
    return new Draft(programs, base, model, List.copyOf(extended));
  }

  /**
   * Returns the draft extended by every operator of {@code additions}. The operators refer to the
   * current text and are applied together; if they conflict, they are applied one after another.
   */
  public Draft with(List<? extends EditOperator> additions) {
    if (additions.isEmpty()) {
      return this;
    }
    Snapshot current = program.snapshot();
    List<se.kth.sonar.text.LineEdit> edits = new ArrayList<>();
    additions.forEach(operator -> edits.add(operator.lower()));
    Snapshot next;
    try {
      next = current.patch(Transaction.of(current, edits)).after();
    } catch (IllegalArgumentException conflicting) {
      Draft draft = this;
      for (EditOperator operator : additions) {
        draft = draft.with(operator);
      }
      return draft;
    }
    List<EditOperator> extended = new ArrayList<>(operators);
    extended.addAll(additions);
    ProgramModel model = programs.model(next, program.topology(), Optional.of(program));
    return new Draft(programs, base, model, List.copyOf(extended));
  }

  /** Returns the program the draft started from. */
  public ProgramModel base() {
    return base;
  }

  /** Returns the program the operators produce. */
  public ProgramModel program() {
    return program;
  }

  /** Returns the operators in order. */
  public List<EditOperator> operators() {
    return operators;
  }

  /** Returns the routers the operators change. */
  public Set<RouterId> routers() {
    Set<RouterId> routers = new LinkedHashSet<>();
    operators.forEach(operator -> routers.add(operator.router()));
    return routers;
  }

  /** Returns whether the operators leave every configuration as it was. */
  public boolean changesNothing() {
    return program.snapshot().equals(base.snapshot());
  }

  /**
   * Returns the objects whose attributes the operators changed, created or removed, compared with
   * the base program.
   */
  public Set<ProgramObject.Ref> touched() {
    return ProgramModel.changedObjects(base, program, base.snapshot().routers());
  }

  /** Returns the hypothesis the operators make against the base snapshot. */
  public Hypothesis hypothesis() {
    return Hypothesis.of(base.snapshot(), operators);
  }
}
