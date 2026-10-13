package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.ProtocolTransfer;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

/** Materialises verifier-observed protocol-transfer policies through the typed IOS grammar. */
public final class ProtocolTransferGenerator {
  private final ClosureEngine closure;
  private final SeedGenerator seeds;
  private final PolicyCutGenerator policyCuts;

  /** Creates the generator from the shared relation closure. */
  public ProtocolTransferGenerator(ClosureEngine closure, SeedGenerator seeds) {
    this.closure = java.util.Objects.requireNonNull(closure, "closure");
    this.seeds = java.util.Objects.requireNonNull(seeds, "seeds");
    this.policyCuts = new PolicyCutGenerator(seeds);
  }

  /**
   * Returns candidates that remove possible deny points, complete a missing policy attachment, or
   * do both atomically.
   */
  public List<Hypothesis> generate(
      ProgramModel program, Verification verification, ProtocolTransfer transfer) {
    if (transfer.attachment().isPresent()) {
      ProtocolTransfer.Attachment attachment = transfer.attachment().orElseThrow();
      List<EditOperator> attachmentOperators =
          seeds.insert(
              program,
              attachment.neighbor(),
              "neighbor-route-map",
              Map.of(
                  "peer",
                  attachment.neighbor().key(),
                  "map",
                  transfer.policy().key(),
                  "direction",
                  List.of(attachment.direction())),
              new Provenance.Inferred("forbidden-flow-cut"));
      if (attachmentOperators.isEmpty()) {
        return List.of();
      }
      Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
      Hypothesis base = Hypothesis.of(program.snapshot(), attachmentOperators);
      unique.put(base.id(), base);
      Set<Prefix> preserved = preservedDestinations(program, verification, attachment, transfer);
      Map<ProgramObject.Ref, Set<Prefix>> completions = new LinkedHashMap<>();
      for (Prefix destination : preserved) {
        for (ProgramObject.Ref list :
            RouteMapEvaluation.completionLists(program, transfer.policy(), Set.of(destination))) {
          completions.computeIfAbsent(list, ignored -> new LinkedHashSet<>()).add(destination);
          addCompletions(
              program,
              attachmentOperators,
              policyCuts.complete(program, list, Set.of(destination)),
              unique);
        }
      }
      for (Map.Entry<ProgramObject.Ref, Set<Prefix>> completion : completions.entrySet()) {
        if (completion.getValue().size() > 1) {
          addCompletions(
              program,
              attachmentOperators,
              policyCuts.complete(program, completion.getKey(), completion.getValue()),
              unique);
        }
      }
      return List.copyOf(unique.values());
    }
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

  private static void addCompletions(
      ProgramModel program,
      List<EditOperator> attachment,
      List<Hypothesis> prerequisites,
      Map<ContentHash, Hypothesis> unique) {
    for (Hypothesis prerequisite : prerequisites) {
      List<EditOperator> operators = new ArrayList<>(prerequisite.operators());
      operators.addAll(attachment);
      combined(program, operators)
          .ifPresent(hypothesis -> unique.putIfAbsent(hypothesis.id(), hypothesis));
    }
  }

  private static Set<Prefix> preservedDestinations(
      ProgramModel program,
      Verification verification,
      ProtocolTransfer.Attachment attachment,
      ProtocolTransfer transfer) {
    ProgramObject neighbor = program.object(attachment.neighbor()).orElse(null);
    if (neighbor == null) {
      return Set.of();
    }
    RouterId remote =
        neighbor
            .value("peer-router")
            .filter(value -> ProgramObject.Ref.isReference(value, "router"))
            .map(ProgramObject.Ref::parse)
            .map(ProgramObject.Ref::router)
            .orElse(null);
    if (remote == null) {
      return Set.of();
    }
    RouterId local = attachment.neighbor().router();
    RouterId before = attachment.direction().equals("out") ? remote : local;
    RouterId after = attachment.direction().equals("out") ? local : remote;
    Set<Prefix> destinations = new LinkedHashSet<>();
    for (Specification specification : verification.verdict().fixed()) {
      Prefix destination = specification.predicate().destination();
      if (!specification.status().expectPresent()
          || RouteMapEvaluation.permits(program, transfer.policy(), destination)) {
        continue;
      }
      boolean crosses =
          verification
              .evidence()
              .traces()
              .getOrDefault(specification.predicate(), List.of())
              .stream()
              .filter(trace -> trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED)
              .anyMatch(trace -> crosses(trace.hops(), before, after));
      if (crosses) {
        destinations.add(destination);
      }
    }
    return Set.copyOf(destinations);
  }

  private static boolean crosses(List<RouterId> hops, RouterId before, RouterId after) {
    for (int at = 1; at < hops.size(); at++) {
      if (hops.get(at - 1).equals(before) && hops.get(at).equals(after)) {
        return true;
      }
    }
    return false;
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

  /** Returns the candidate made of {@code operators}, unless they do not form one transaction. */
  private static Optional<Hypothesis> combined(ProgramModel program, List<EditOperator> operators) {
    try {
      return Optional.of(Hypothesis.of(program.snapshot(), operators));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return Optional.empty();
    }
  }
}
