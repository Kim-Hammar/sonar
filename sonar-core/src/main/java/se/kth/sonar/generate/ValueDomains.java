package se.kth.sonar.generate;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.Verification;

/**
 * Supplies candidate values for slots from the domain rules of the relation schema.
 *
 * <p>Candidates keep their provenance, so observed values rank before invented ones without any
 * extra rule. Within a rule, values keep the order of their origin: related values, then values in
 * scope by distance from the edited router, then verifier observations in specification order. A
 * slot draws at most {@link Bounds#maxValuesPerSlot()} values.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class ValueDomains {
  private final RelationSchema schema;
  private final Bounds bounds;

  /**
   * A candidate value for the slots of one domain rule.
   *
   * @param values one token per slot
   * @param provenance where the values come from
   */
  public record Candidate(List<String> values, Provenance provenance) {
    /** Copies the values. */
    public Candidate {
      values = List.copyOf(values);
      Objects.requireNonNull(provenance, "provenance");
    }
  }

  /** Creates the domains of {@code schema}. */
  public ValueDomains(RelationSchema schema, Bounds bounds) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.bounds = Objects.requireNonNull(bounds, "bounds");
  }

  /** Returns candidate values for {@code slots} of {@code production} on {@code router}. */
  public List<Candidate> candidates(
      ProgramModel program,
      Verification verification,
      RouterId router,
      Production production,
      List<String> slots) {
    throw new NotImplemented("ValueDomains.candidates");
  }
}
