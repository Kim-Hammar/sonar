package se.kth.sonar.generate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.state.ProtocolTransfer;
import se.kth.sonar.verify.Verification;

/** Materialises verifier-observed protocol-transfer policies through the typed IOS grammar. */
public final class ProtocolTransferGenerator {
  private final ClosureEngine closure;

  /** Creates the generator from the shared relation closure. */
  public ProtocolTransferGenerator(ClosureEngine closure) {
    this.closure = java.util.Objects.requireNonNull(closure, "closure");
  }

  /**
   * Returns candidates that remove possible deny points, complete a missing policy attachment, or
   * do both atomically.
   */
  public List<Hypothesis> generate(
      ProgramModel program, Verification verification, ProtocolTransfer transfer) {
    Set<ClosureEngine.Obligation> obligations = liveness(program, transfer.policy());
    Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    if (!obligations.isEmpty()) {
      closure
          .close(program, verification, List.of(), obligations)
          .forEach(hypothesis -> unique.putIfAbsent(hypothesis.id(), hypothesis));
    }
    for (ProgramObject.Ref blocker : transfer.blockers()) {
      ProgramObject entry = program.object(blocker).orElse(null);
      if (entry == null) {
        continue;
      }
      Statement statement = openingStatement(program, entry);
      if (statement == null || !entry.value("action").filter("deny"::equals).isPresent()) {
        continue;
      }
      EditOperator operator =
          new EditOperator.ReplaceSlots(
              statement, "action", List.of("permit"), new Provenance.Inferred("protocol-transfer"));
      closure
          .close(program, verification, List.of(operator), obligations)
          .forEach(hypothesis -> unique.putIfAbsent(hypothesis.id(), hypothesis));
    }
    return List.copyOf(unique.values());
  }

  private static Set<ClosureEngine.Obligation> liveness(
      ProgramModel program, ProgramObject.Ref policy) {
    Set<ClosureEngine.Obligation> result = new LinkedHashSet<>();
    for (RelationInstance instance : program.instances("route-map-liveness")) {
      if (!policy.equals(instance.roles().get("map"))) {
        continue;
      }
      for (Requirement requirement : instance.violated()) {
        if (requirement.signals()) {
          result.add(new ClosureEngine.Obligation(instance, requirement));
        }
      }
    }
    return Set.copyOf(result);
  }

  private static Statement openingStatement(ProgramModel program, ProgramObject entry) {
    for (var span : entry.statements()) {
      for (Statement statement : program.statements(span.router())) {
        if (statement.span().equals(span)
            && statement
                .match()
                .map(match -> match.production().id())
                .orElse("")
                .equals("route-map")) {
          return statement;
        }
      }
    }
    return null;
  }
}
