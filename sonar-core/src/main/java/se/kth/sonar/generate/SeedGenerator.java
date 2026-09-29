package se.kth.sonar.generate;

import java.util.List;
import java.util.Objects;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.verify.Verification;

/**
 * Proposes the first operator of every candidate at a site: deleting one of the site's statements,
 * replacing one slot value with a value from the slot's domains, or inserting a statement of a
 * production that the site's context allows, with values from the domains.
 */
@Placeholder
@SuppressWarnings("UnusedVariable")
public final class SeedGenerator {
  private final Grammar grammar;
  private final ValueDomains domains;
  private final Bounds bounds;

  /** Creates a generator over {@code grammar}. */
  public SeedGenerator(Grammar grammar, ValueDomains domains, Bounds bounds) {
    this.grammar = Objects.requireNonNull(grammar, "grammar");
    this.domains = Objects.requireNonNull(domains, "domains");
    this.bounds = Objects.requireNonNull(bounds, "bounds");
  }

  /** Returns the seed operators at {@code site}. */
  public List<EditOperator> seeds(
      ProgramModel program, Verification verification, ProgramObject.Ref site) {
    throw new NotImplemented("SeedGenerator.seeds");
  }
}
