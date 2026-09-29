package se.kth.sonar.batfish;

import java.util.Set;
import org.batfish.datamodel.table.TableAnswerElement;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.common.Placeholder;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.SpecificationSet;

/**
 * Reads which forwarding predicates hold from the answer of the forwarding behaviour table
 * question, with the benchmark's meaning of reachability, isolation, waypointing and load
 * balancing.
 */
@Placeholder
final class PredicateMiner {
  /** Returns the predicates of {@code specifications} that hold according to {@code table}. */
  Set<ForwardingPredicate> holding(TableAnswerElement table, SpecificationSet specifications) {
    throw new NotImplemented("PredicateMiner.holding");
  }
}
