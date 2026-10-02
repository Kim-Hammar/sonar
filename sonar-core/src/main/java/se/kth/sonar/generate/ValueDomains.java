package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.grammar.Lexeme;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.grammar.Symbol;
import se.kth.sonar.network.Ipv4;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationEvaluator;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.DomainRule;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.Verification;

/**
 * Supplies candidate values for slots from the domain rules of the relation schema.
 *
 * <p>Candidates keep their provenance, so observed values rank before invented ones without any
 * extra rule. Within a rule, values keep the order of their origin: related values, then values in
 * scope by distance from the edited router, then verifier observations in specification order. A
 * slot that no rule covers draws the values it has in statements of the same production anywhere in
 * the network, nearest router first. A slot group draws at most {@link Bounds#maxValuesPerSlot()}
 * values.
 */
public final class ValueDomains {
  private static final int NEARBY_PREFIX = 24;

  private final RelationSchema schema;
  private final Bounds bounds;
  private final RelationEvaluator evaluator;

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
    this.evaluator = new RelationEvaluator(schema);
  }

  /**
   * Returns the groups of slots of {@code production} that domain rules fill, in rule order, then
   * each slot no rule covers on its own.
   */
  public List<List<String>> groups(Production production) {
    List<List<String>> groups = new ArrayList<>();
    for (DomainRule rule : schema.domains(production)) {
      if (!groups.contains(rule.slots())) {
        groups.add(rule.slots());
      }
    }
    for (Symbol.Slot slot : production.slots()) {
      if (groups.stream().noneMatch(group -> group.contains(slot.name()))) {
        groups.add(List.of(slot.name()));
      }
    }
    return groups;
  }

  /** Returns the rules for {@code slots} of {@code production}, or the implicit observed rule. */
  private List<DomainRule> rules(Production production, List<String> slots) {
    List<DomainRule> rules =
        schema.domains(production).stream().filter(rule -> rule.slots().equals(slots)).toList();
    if (rules.isEmpty() && slots.size() == 1) {
      return List.of(
          new DomainRule(
              production, slots, new DomainRule.Origin.Observed(DomainRule.Scope.NETWORK)));
    }
    return rules;
  }

  /**
   * Returns candidate values for the slot group {@code slots} of {@code production} on {@code
   * router}; values related to {@code completing} come from that relation instance. A literal
   * listed after the observed values of the network is one the network does not hold.
   */
  public List<Candidate> candidates(
      ProgramModel program,
      Verification verification,
      RouterId router,
      Production production,
      List<String> slots,
      Optional<RelationInstance> completing) {
    List<Symbol.Slot> declared =
        slots.stream().map(name -> production.slot(name).orElseThrow()).toList();
    List<Candidate> candidates = new ArrayList<>();
    Set<List<String>> seen = new HashSet<>();
    boolean searched = false;
    for (DomainRule rule : rules(production, slots)) {
      boolean unobserved = searched && rule.origin() instanceof DomainRule.Origin.Literals;
      searched |= rule.origin() instanceof DomainRule.Origin.Observed;
      for (String value : values(rule, program, verification, router, completing, declared)) {
        Optional<List<String>> tokens = tokens(declared, value);
        if (tokens.isPresent() && seen.add(tokens.get())) {
          candidates.add(
              new Candidate(
                  tokens.get(),
                  unobserved ? new Provenance.Unobserved(rule) : new Provenance.Domain(rule)));
          if (candidates.size() == bounds.maxValuesPerSlot()) {
            return candidates;
          }
        }
      }
    }
    return candidates;
  }

  private List<String> values(
      DomainRule rule,
      ProgramModel program,
      Verification verification,
      RouterId router,
      Optional<RelationInstance> completing,
      List<Symbol.Slot> declared) {
    return switch (rule.origin()) {
      case DomainRule.Origin.Values values ->
          inScope(program, values.object(), values.scope(), router).stream()
              .flatMap(object -> object.values(values.attribute()).stream())
              .toList();
      case DomainRule.Origin.Names names ->
          inScope(program, names.object(), names.scope(), router).stream()
              .filter(object -> object.ref().key().size() == 1)
              .map(object -> object.ref().key().getFirst())
              .toList();
      case DomainRule.Origin.Related related ->
          completing
              .filter(instance -> instance.relation().equals(related.relation()))
              .flatMap(instance -> evaluator.valueAt(program, instance, related.path()))
              .orElse(List.of());
      case DomainRule.Origin.Verifier verifier ->
          observed(program, verification, verifier.observation(), declared);
      case DomainRule.Origin.Fresh _ -> fresh(program, rule.production(), declared, router);
      case DomainRule.Origin.Observed observed ->
          observedValues(
              program, rule.production(), rule.slots().getFirst(), observed.scope(), router);
      case DomainRule.Origin.Literals _ ->
          declared.size() == 1
                  && declared.getFirst().sort().lexeme() instanceof Lexeme.Enumeration e
              ? e.literals()
              : List.of();
    };
  }

  /**
   * Returns the objects of {@code type} that {@code scope} admits, nearest to {@code router} first.
   */
  private static List<ProgramObject> inScope(
      ProgramModel program, String type, DomainRule.Scope scope, RouterId router) {
    Set<RouterId> neighbours = new HashSet<>(program.topology().neighbours(router));
    List<ProgramObject> own = new ArrayList<>();
    List<ProgramObject> near = new ArrayList<>();
    List<ProgramObject> far = new ArrayList<>();
    for (ProgramObject object : program.objects(type)) {
      RouterId owner = object.ref().router();
      if (owner.equals(router)) {
        own.add(object);
      } else if (neighbours.contains(owner)) {
        near.add(object);
      } else {
        far.add(object);
      }
    }
    List<ProgramObject> result = new ArrayList<>(own);
    if (scope != DomainRule.Scope.ROUTER) {
      result.addAll(near);
    }
    if (scope == DomainRule.Scope.NETWORK) {
      result.addAll(far);
    }
    return result;
  }

  /** Returns the values {@code slot} has in statements of {@code production}, nearest first. */
  private static List<String> observedValues(
      ProgramModel program,
      Production production,
      String slot,
      DomainRule.Scope scope,
      RouterId router) {
    Set<RouterId> neighbours = new HashSet<>(program.topology().neighbours(router));
    List<RouterId> routers = new ArrayList<>();
    routers.add(router);
    List<RouterId> far = new ArrayList<>();
    for (RouterId other : program.snapshot().routers()) {
      if (other.equals(router)) {
        continue;
      }
      if (neighbours.contains(other)) {
        routers.add(other);
      } else {
        far.add(other);
      }
    }
    if (scope == DomainRule.Scope.ROUTER) {
      routers = List.of(router);
    } else if (scope == DomainRule.Scope.NETWORK) {
      routers.addAll(far);
    }
    Set<String> values = new LinkedHashSet<>();
    for (RouterId each : routers) {
      for (Statement statement : program.statements(each)) {
        statement
            .match()
            .filter(match -> match.production().equals(production))
            .map(match -> match.values(slot))
            .filter(tokens -> tokens.size() == 1)
            .ifPresent(tokens -> values.add(tokens.getFirst()));
      }
    }
    return new ArrayList<>(values);
  }

  /**
   * Returns what the verifier observed about the unresolved specifications: destinations, or the
   * source, waypoint or route-holding routers, as names for a name slot and as their loopback
   * addresses for an address slot.
   */
  private static List<String> observed(
      ProgramModel program,
      Verification verification,
      DomainRule.Observation what,
      List<Symbol.Slot> declared) {
    boolean addresses =
        declared.size() == 1 && declared.getFirst().sort().lexeme() instanceof Lexeme.Address;
    Set<String> values = new LinkedHashSet<>();
    for (Specification specification : verification.verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      List<RouterId> routers = new ArrayList<>();
      switch (what) {
        case DESTINATION -> values.add(predicate.destination().toString());
        case SOURCE -> routers.add(predicate.source());
        case WAYPOINT -> {
          if (predicate instanceof ForwardingPredicate.Waypointing waypointing) {
            routers.add(waypointing.waypoint());
          }
        }
        case ROUTE_HOLDER ->
            routers.addAll(
                verification
                    .evidence()
                    .routeHolders()
                    .getOrDefault(predicate.destination(), Set.of()));
      }
      for (RouterId router : routers) {
        if (!addresses) {
          values.add(router.name());
        } else {
          program
              .object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()))
              .flatMap(owner -> owner.value("loopback-address"))
              .ifPresent(values::add);
        }
      }
    }
    return new ArrayList<>(values);
  }

  /**
   * Returns values not used in the program: one name for a name slot, and for an address slot a few
   * unused addresses next to the addresses that {@code router}'s statements of the production
   * already use, nearest first.
   */
  private static List<String> fresh(
      ProgramModel program, Production production, List<Symbol.Slot> declared, RouterId router) {
    if (declared.size() != 1) {
      return List.of();
    }
    if (declared.getFirst().sort().lexeme() instanceof Lexeme.Address) {
      return freshAddresses(program, production, declared.getFirst().name(), router);
    }
    if (!(declared.getFirst().sort().lexeme() instanceof Lexeme.Name)) {
      return List.of();
    }
    Set<String> used = new HashSet<>();
    for (ProgramObject object : program.objects()) {
      used.addAll(object.ref().key());
      object.attributes().values().forEach(used::addAll);
    }
    String stem = "SONAR_" + production.id().toUpperCase(Locale.ROOT).replace('-', '_');
    String candidate = stem;
    for (int suffix = 2; used.contains(candidate); suffix++) {
      candidate = stem + "_" + suffix;
    }
    return List.of(candidate);
  }

  private static List<String> freshAddresses(
      ProgramModel program, Production production, String slot, RouterId router) {
    Set<Ipv4> used = new TreeSet<>();
    for (ProgramObject object : program.objects()) {
      for (List<String> values : object.attributes().values()) {
        values.forEach(value -> Ipv4.tryParse(value).ifPresent(used::add));
      }
    }
    List<String> result = new ArrayList<>();
    for (Statement statement : program.statements(router)) {
      Optional<Ipv4> own =
          statement
              .match()
              .filter(match -> match.production().equals(production))
              .map(match -> match.values(slot))
              .filter(values -> values.size() == 1)
              .flatMap(values -> Ipv4.tryParse(values.getFirst()));
      if (own.isEmpty()) {
        continue;
      }
      Prefix block = new Prefix(own.get().and(Ipv4.mask(NEARBY_PREFIX)), NEARBY_PREFIX);
      for (Ipv4 host : CiscoIosSemantics.hosts(block, used)) {
        String text = host.toString();
        if (!result.contains(text)) {
          result.add(text);
        }
      }
    }
    return result;
  }

  /** Converts one origin value into the tokens of the slot group, if they fit its sorts. */
  private static Optional<List<String>> tokens(List<Symbol.Slot> declared, String value) {
    if (declared.size() == 1) {
      return declared.getFirst().accepts(value) ? Optional.of(List.of(value)) : Optional.empty();
    }
    Optional<Prefix> prefix = Prefix.tryParse(value);
    if (prefix.isEmpty()) {
      return Optional.empty();
    }
    String second =
        declared.get(1).sort().lexeme() instanceof Lexeme.Wildcard
            ? new se.kth.sonar.network.Ipv4(~prefix.get().mask().bits()).toString()
            : prefix.get().mask().toString();
    List<String> tokens = List.of(prefix.get().network().toString(), second);
    return declared.get(0).accepts(tokens.get(0)) && declared.get(1).accepts(second)
        ? Optional.of(tokens)
        : Optional.empty();
  }
}
