package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RouteMapEvaluation;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.Verification;

/** Completes the two policy attachments through which an observed BGP route must transfer. */
public final class BgpPolicyPathGenerator {
  private final SeedGenerator seeds;
  private final PolicyCutGenerator policyCuts;

  /** Creates the generator from the typed insertion stage. */
  public BgpPolicyPathGenerator(SeedGenerator seeds) {
    this.seeds = java.util.Objects.requireNonNull(seeds, "seeds");
    this.policyCuts = new PolicyCutGenerator(seeds);
  }

  /**
   * Returns atomic sender-export and receiver-import policy paths for {@code receiverSite}.
   *
   * <p>The verifier establishes the direction: the peer holds a route that the receiver lacks.
   * Program analysis retains only route maps that permit an observed missing destination, either
   * already or after completing one prefix-list dependency in the same transaction. Destinations
   * are closed independently because distinct advertisements crossing one session need not use the
   * same policy path. The all-destination closure is retained when it exists.
   */
  public List<Hypothesis> generate(
      ProgramModel program, Verification verification, ProgramObject.Ref receiverSite) {
    if (!receiverSite.type().equals("bgp-neighbor")) {
      return List.of();
    }
    ProgramObject receiver = program.object(receiverSite).orElse(null);
    RouterId senderRouter = peerRouter(receiver);
    if (receiver == null || senderRouter == null) {
      return List.of();
    }
    ProgramObject sender = reciprocal(program, senderRouter, receiverSite.router());
    if (sender == null) {
      return List.of();
    }
    Set<Prefix> destinations =
        missingFrom(program, verification, receiverSite.router(), senderRouter);
    if (destinations.isEmpty()) {
      return List.of();
    }
    Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    Provenance provenance = new Provenance.Inferred("verifier-observed BGP policy path");
    for (Prefix destination : destinations.stream().sorted().toList()) {
      addPaths(
          program,
          sender,
          receiver,
          policyChoices(program, senderRouter, Set.of(destination)),
          policyChoices(program, receiverSite.router(), Set.of(destination)),
          provenance,
          unique);
    }
    if (destinations.size() > 1) {
      addPaths(
          program,
          sender,
          receiver,
          policyChoices(program, senderRouter, destinations),
          policyChoices(program, receiverSite.router(), destinations),
          provenance,
          unique);
    }
    return List.copyOf(unique.values());
  }

  private void addPaths(
      ProgramModel program,
      ProgramObject sender,
      ProgramObject receiver,
      List<PolicyChoice> exports,
      List<PolicyChoice> imports,
      Provenance provenance,
      Map<ContentHash, Hypothesis> unique) {
    for (PolicyChoice export : exports) {
      for (PolicyChoice inbound : imports) {
        List<EditOperator> operators = new ArrayList<>();
        operators.addAll(export.prerequisites());
        operators.addAll(inbound.prerequisites());
        operators.addAll(attach(program, sender, export.routeMap(), "out", provenance));
        operators.addAll(attach(program, receiver, inbound.routeMap(), "in", provenance));
        try {
          if (!operators.isEmpty()) {
            Hypothesis hypothesis = Hypothesis.of(program.snapshot(), operators);
            unique.putIfAbsent(hypothesis.id(), hypothesis);
          }
        } catch (IllegalArgumentException | IllegalStateException invalid) {
          // Another pair may still be a valid typed transaction.
        }
      }
    }
  }

  private List<EditOperator> attach(
      ProgramModel program,
      ProgramObject neighbor,
      ProgramObject routeMap,
      String direction,
      Provenance provenance) {
    Set<se.kth.sonar.text.SourceSpan> spans = new HashSet<>(neighbor.statements());
    Statement existing =
        program.statements(neighbor.ref().router()).stream()
            .filter(statement -> spans.contains(statement.span()))
            .filter(
                statement -> {
                  Production.Match match = statement.match().orElse(null);
                  return match != null
                      && match.production().id().equals("neighbor-route-map")
                      && match.values("direction").equals(List.of(direction));
                })
            .findFirst()
            .orElse(null);
    String name = routeMap.ref().key().getFirst();
    if (existing != null) {
      if (existing.match().orElseThrow().values("map").equals(List.of(name))) {
        return List.of();
      }
      return List.of(new EditOperator.ReplaceSlots(existing, "map", List.of(name), provenance));
    }
    return seeds.insert(
        program,
        neighbor.ref(),
        "neighbor-route-map",
        Map.of(
            "peer", neighbor.ref().key(),
            "map", routeMap.ref().key(),
            "direction", List.of(direction)),
        provenance);
  }

  private List<PolicyChoice> policyChoices(
      ProgramModel program, RouterId router, Set<Prefix> destinations) {
    List<PolicyChoice> choices = new ArrayList<>();
    for (ProgramObject object : program.objectsOf(router)) {
      if (!object.ref().type().equals("route-map")) {
        continue;
      }
      if (destinations.stream()
          .allMatch(prefix -> RouteMapEvaluation.permits(program, object.ref(), prefix))) {
        choices.add(new PolicyChoice(object, List.of()));
        continue;
      }
      for (ProgramObject.Ref list :
          RouteMapEvaluation.completionLists(program, object.ref(), destinations)) {
        for (Hypothesis completion : policyCuts.complete(program, list, destinations)) {
          choices.add(new PolicyChoice(object, completion.operators()));
        }
      }
    }
    choices.sort(
        Comparator.comparing((PolicyChoice choice) -> choice.routeMap().has("used"))
            .thenComparing(choice -> choice.routeMap().ref().toString())
            .thenComparingInt(choice -> choice.prerequisites().size()));
    return List.copyOf(choices);
  }

  private record PolicyChoice(ProgramObject routeMap, List<EditOperator> prerequisites) {
    private PolicyChoice {
      prerequisites = List.copyOf(prerequisites);
    }
  }

  private static Set<Prefix> missingFrom(
      ProgramModel program, Verification verification, RouterId receiver, RouterId sender) {
    Set<Prefix> result = new LinkedHashSet<>();
    for (Specification specification : verification.verdict().unresolved()) {
      Set<RouterId> holders =
          verification
              .evidence()
              .routeHolders()
              .getOrDefault(specification.predicate().destination(), Set.of());
      if (specification.status().expectPresent()
          && RouterEvidence.noRouteAtSource(verification.evidence(), specification.predicate())
          && holders.contains(sender)
          && RouterEvidence.routeFrontier(
                  program.topology(), specification.predicate().source(), holders)
              .contains(RouterEvidence.Link.of(receiver, sender))) {
        result.add(specification.predicate().destination());
      }
    }
    return Set.copyOf(result);
  }

  private static ProgramObject reciprocal(ProgramModel program, RouterId router, RouterId peer) {
    String peerRef = new ProgramObject.Ref("router", peer, List.of()).toString();
    return program.objectsOf(router).stream()
        .filter(object -> object.ref().type().equals("bgp-neighbor"))
        .filter(object -> object.value("peer-router").filter(peerRef::equals).isPresent())
        .findFirst()
        .orElse(null);
  }

  private static RouterId peerRouter(ProgramObject neighbor) {
    if (neighbor == null) {
      return null;
    }
    return neighbor
        .value("peer-router")
        .filter(value -> ProgramObject.Ref.isReference(value, "router"))
        .map(ProgramObject.Ref::parse)
        .map(ProgramObject.Ref::router)
        .orElse(null);
  }
}
