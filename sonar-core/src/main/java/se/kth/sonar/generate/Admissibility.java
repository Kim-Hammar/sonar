package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.RouterId;

/**
 * Rejects candidates that cannot be repairs before any of them is verified: patches that do not
 * apply, text that the grammar cannot parse, statements that name objects which do not exist, new
 * violations of hard requirements, changes to nothing that the program depends on, and duplicates,
 * meaning candidates that produce a snapshot already produced by a cheaper candidate.
 */
public final class Admissibility {
  private final ProgramService programs;
  private final ExecutionContext context;

  /** Creates a filter that builds candidate programs with {@code programs} on {@code context}. */
  public Admissibility(ProgramService programs, ExecutionContext context) {
    this.programs = Objects.requireNonNull(programs, "programs");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Returns the admissible candidates, in their original order. */
  public List<Hypothesis> admit(RepairState state, List<Hypothesis> candidates) {
    ProgramModel base = state.program();
    Set<Violation> tolerated = violations(base);
    List<Optional<ContentHash>> results =
        context.compute(candidates, candidate -> resultOf(state, base, tolerated, candidate));
    Map<ContentHash, Hypothesis> bestByResult = new HashMap<>();
    List<Hypothesis> admitted = new ArrayList<>();
    for (int at = 0; at < candidates.size(); at++) {
      if (results.get(at).isEmpty()) {
        continue;
      }
      ContentHash result = results.get(at).get();
      Hypothesis candidate = candidates.get(at);
      Hypothesis current = bestByResult.get(result);
      if (current == null || cheaper(candidate, current)) {
        bestByResult.put(result, candidate);
      }
    }
    Set<Hypothesis> kept = new HashSet<>(bestByResult.values());
    for (Hypothesis candidate : candidates) {
      if (kept.contains(candidate)) {
        admitted.add(candidate);
      }
    }
    return admitted;
  }

  /** Returns the identifier of the snapshot {@code candidate} produces, if it is admissible. */
  private Optional<ContentHash> resultOf(
      RepairState state, ProgramModel base, Set<Violation> tolerated, Hypothesis candidate) {
    Snapshot.Delta delta;
    try {
      delta = state.snapshot().patch(candidate.transaction());
    } catch (IllegalArgumentException | IllegalStateException inapplicable) {
      return Optional.empty();
    }
    if (delta.changesNothing()) {
      return Optional.empty();
    }
    ProgramModel model = programs.model(delta.after(), base.topology(), Optional.of(base));
    if (!parses(base, model, delta.changed())
        || !violations(model).stream().allMatch(tolerated::contains)
        || !routesAreWellFounded(base, model, delta.changed())
        || ProgramModel.changedObjects(base, model, delta.changed()).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(delta.after().id());
  }

  private record Violation(
      String relation, Set<ProgramObject.Ref> objects, Requirement requirement) {}

  private static Set<Violation> violations(ProgramModel model) {
    Set<Violation> violations = new HashSet<>();
    for (RelationInstance instance : model.violated()) {
      for (Requirement requirement : instance.violated()) {
        if (requirement.strength() == Requirement.Strength.HARD) {
          violations.add(
              new Violation(
                  instance.relation(), Set.copyOf(instance.roles().values()), requirement));
        }
      }
    }
    return violations;
  }

  /**
   * Returns whether every new line on the changed routers matches the grammar and names existing
   * objects.
   */
  private static boolean parses(ProgramModel before, ProgramModel after, Set<RouterId> changed) {
    for (RouterId router : changed) {
      Set<String> known = new HashSet<>();
      for (Statement statement : before.statements(router)) {
        if (!statement.matched()) {
          known.add(statement.text());
        }
      }
      for (Statement statement : after.statements(router)) {
        if (!statement.matched() && !known.contains(statement.text())) {
          return false;
        }
      }
      Set<String> danglingBefore = new HashSet<>();
      before.dangling().stream()
          .filter(span -> span.router().equals(router))
          .forEach(span -> danglingBefore.add(before.snapshot().text(router).line(span.first())));
      for (var span : after.dangling()) {
        if (span.router().equals(router)
            && !danglingBefore.contains(after.snapshot().text(router).line(span.first()))) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Returns whether every new interface-only static route has a forwarding endpoint.
   *
   * <p>A static route through a physical link can forward an arbitrary remote prefix. An interface
   * without a topological peer, such as a loopback, can only originate its own connected subnet;
   * pointing an unrelated prefix at it terminates traffic locally instead of forwarding it. Such a
   * candidate can make a reachability predicate pass while making a required path impossible, so it
   * is not a semantically valid repair.
   */
  private static boolean routesAreWellFounded(
      ProgramModel before, ProgramModel after, Set<RouterId> changed) {
    for (RouterId router : changed) {
      for (ProgramObject route : after.objectsOf(router)) {
        if (!route.ref().type().equals("static-route")) {
          continue;
        }
        Optional<ProgramObject> previous = before.object(route.ref());
        if (previous.isPresent() && previous.get().attributes().equals(route.attributes())) {
          continue;
        }
        Optional<String> interfaceRef = route.value("interface");
        if (interfaceRef.isEmpty()) {
          continue;
        }
        ProgramObject.Ref ref =
            ProgramObject.Ref.isReference(interfaceRef.get(), "interface")
                ? ProgramObject.Ref.parse(interfaceRef.get())
                : new ProgramObject.Ref("interface", router, List.of(interfaceRef.get()));
        String interfaceName = ref.key().getFirst();
        if (after.topology().peer(new Topology.Endpoint(router, interfaceName)).isPresent()) {
          continue;
        }
        Optional<Prefix> routePrefix = route.value("prefix").flatMap(Prefix::tryParse);
        Optional<Prefix> interfacePrefix =
            after.object(ref).flatMap(object -> object.value("subnet")).flatMap(Prefix::tryParse);
        if (routePrefix.isEmpty()
            || interfacePrefix.isEmpty()
            || !interfacePrefix.get().contains(routePrefix.get())) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean cheaper(Hypothesis candidate, Hypothesis current) {
    if (candidate.cost() != current.cost()) {
      return candidate.cost() < current.cost();
    }
    return candidate.id().compareTo(current.id()) < 0;
  }
}
