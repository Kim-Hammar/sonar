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
   */
  record Forced(String relation, Requirement requirement) implements Provenance {
    /** Checks the components. */
    public Forced {
      Objects.requireNonNull(relation, "relation");
      Objects.requireNonNull(requirement, "requirement");
    }

    @Override
    public ValueSupport support() {
      return ValueSupport.OBSERVED;
    }
  }
}
