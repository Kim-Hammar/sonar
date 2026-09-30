package se.kth.sonar.generate;

import java.util.Objects;
import se.kth.sonar.relation.DomainRule;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.relation.ValueSupport;

/** Why an edit operator carries the values it carries. */
public sealed interface Provenance {
  /** Returns whether the values were observed or invented. */
  ValueSupport support();

  /** The operator removes or keeps what the configuration already says. */
  record Existing() implements Provenance {
    @Override
    public ValueSupport support() {
      return ValueSupport.OBSERVED;
    }
  }

  /**
   * The values come from a domain rule.
   *
   * @param rule the rule
   */
  record Domain(DomainRule rule) implements Provenance {
    /** Checks the rule. */
    public Domain {
      Objects.requireNonNull(rule, "rule");
    }

    @Override
    public ValueSupport support() {
      return rule.support();
    }
  }

  /**
   * The values are required by a requirement of a relation instance that the candidate completes.
   *
   * @param relation the relation's name
   * @param requirement the requirement
   * @param support whether the values were observed or invented
   */
  record Forced(String relation, Requirement requirement, ValueSupport support)
      implements Provenance {
    /** Checks the components. */
    public Forced {
      Objects.requireNonNull(relation, "relation");
      Objects.requireNonNull(requirement, "requirement");
      Objects.requireNonNull(support, "support");
    }

    /** Forces observed values. */
    public Forced(String relation, Requirement requirement) {
      this(relation, requirement, ValueSupport.OBSERVED);
    }

    /** Returns the requirement without the support of the values. */
    public Restored restored() {
      return new Restored(relation, requirement);
    }
  }

  /**
   * A requirement of a relation that a candidate restores.
   *
   * @param relation the relation's name
   * @param requirement the requirement
   */
  record Restored(String relation, Requirement requirement) {}
}
