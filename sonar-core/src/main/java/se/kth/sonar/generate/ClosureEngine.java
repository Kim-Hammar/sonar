package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.relation.AttributePath;
import se.kth.sonar.relation.Constraint;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.relation.RelationType;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.Verification;

/**
 * Completes a seed into hypotheses that keep the relations it touches, so that one hypothesis
 * repairs every side of a fault, such as both ends of a BGP session.
 *
 * <p>After applying the seed, every relation instance that involves a changed object is checked,
 * together with the instances the caller asks to repair. A violated hard requirement is completed:
 * {@code exists} inserts the missing statement or object, {@code equal} and {@code agree} align one
 * side with the other, and {@code resolves} renames the reference or creates the referenced object;
 * {@code distinct} has no completion and discards the draft. A violated soft requirement adds the
 * completed draft as an alternative. Completion repeats until nothing is violated, and stops at the
 * bounds. Requirements the base program already violated are left alone unless asked for.
 *
 * <p>The alternatives of a completion come likeliest first, and drafts are completed best first by
 * how far they depart from the first choices: a draft's rank is the sum of the positions of the
 * alternatives it took, and taking a soft alternative counts one more. The hypotheses a bounded
 * closure returns therefore vary every choice a little before varying any one choice a lot.
 */
public final class ClosureEngine {
  private static final int MAX_ALTERNATIVES = 8;
  private static final int MAX_COMPLETIONS = 8;

  private final ProgramService programs;
  private final RelationSchema schema;
  private final SeedGenerator seeds;
  private final Bounds bounds;

  /**
   * A requirement of a relation instance that a closure must satisfy.
   *
   * @param instance the instance
   * @param requirement the requirement
   */
  public record Obligation(RelationInstance instance, Requirement requirement) {
    /** Checks the components. */
    public Obligation {
      Objects.requireNonNull(instance, "instance");
      Objects.requireNonNull(requirement, "requirement");
    }
  }

  /**
   * Creates an engine that models drafts with {@code programs} and fills statements like {@code
   * seeds}.
   */
  public ClosureEngine(ProgramService programs, SeedGenerator seeds, Bounds bounds) {
    this.programs = Objects.requireNonNull(programs, "programs");
    this.schema = programs.dialect().schema();
    this.seeds = Objects.requireNonNull(seeds, "seeds");
    this.bounds = Objects.requireNonNull(bounds, "bounds");
  }

  /**
   * Returns the completed hypotheses of {@code seed}, which may be empty for a closure that only
   * repairs {@code obligations}; required completions come first, soft alternatives later.
   */
  public List<Hypothesis> close(
      ProgramModel program,
      Verification verification,
      List<? extends EditOperator> seed,
      Set<Obligation> obligations) {
    Draft start;
    try {
      start = Draft.of(programs, program).with(seed);
    } catch (IllegalArgumentException | IllegalStateException inapplicable) {
      return List.of();
    }
    Set<Key> baseline = new HashSet<>();
    for (RelationInstance instance : program.violated()) {
      for (Requirement requirement : instance.violated()) {
        if (!obligations.contains(new Obligation(instance, requirement))) {
          baseline.add(new Key(instance.relation(), instance.roles().values(), requirement));
        }
      }
    }
    List<Hypothesis> results = new ArrayList<>();
    Frontier open = new Frontier();
    open.add(start, 0);
    int depth = 0;
    while (!open.isEmpty() && results.size() < MAX_ALTERNATIVES) {
      Ranked ranked = open.next();
      Draft draft = ranked.draft();
      Pending pending = pending(draft, baseline, obligations);
      if (pending.blocking().isEmpty()) {
        if (!draft.changesNothing()) {
          try {
            results.add(draft.hypothesis());
          } catch (IllegalArgumentException empty) {
            continue;
          }
        }
        pending.extendable().stream()
            .findFirst()
            .ifPresent(next -> extend(open, draft, ranked.rank() + 1, verification, next));
        continue;
      }
      if (draft.operators().size() >= bounds.maxOperators()) {
        continue;
      }
      extend(open, draft, ranked.rank(), verification, pending.blocking().getFirst());
      depth++;
      if (depth > bounds.maxClosureDepth() * MAX_ALTERNATIVES * MAX_COMPLETIONS) {
        break;
      }
    }
    return results;
  }

  /**
   * Returns hypotheses that atomically give both sides of a satisfied agreement {@code target},
   * then close every relation affected by those edits.
   */
  public List<Hypothesis> reviseAgreement(
      ProgramModel program,
      Verification verification,
      RelationInstance instance,
      Requirement requirement,
      List<String> target) {
    if (!(requirement.constraint() instanceof Constraint.Agree agree)
        || target.isEmpty()
        || !programs.evaluator().applies(program, instance, requirement)) {
      return List.of();
    }
    List<Draft> drafts = List.of(Draft.of(programs, program));
    for (AttributePath path : List.of(agree.left(), agree.right())) {
      List<Draft> revised = new ArrayList<>();
      for (Draft draft : drafts) {
        if (programs
            .evaluator()
            .valueAt(draft.program(), instance, path)
            .filter(target::equals)
            .isPresent()) {
          revised.add(draft);
          continue;
        }
        Completer completer = new Completer(draft, verification, instance, requirement);
        for (List<EditOperator> operators : completer.set(path, target)) {
          extended(draft, operators).ifPresent(revised::add);
          if (revised.size() >= MAX_COMPLETIONS) {
            break;
          }
        }
      }
      drafts = revised;
      if (drafts.isEmpty()) {
        return List.of();
      }
    }
    Map<se.kth.sonar.common.ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    for (Draft draft : drafts) {
      for (Hypothesis hypothesis : close(program, verification, draft.operators(), Set.of())) {
        unique.putIfAbsent(hypothesis.id(), hypothesis);
      }
    }
    return List.copyOf(unique.values());
  }

  /**
   * A draft waiting to be completed.
   *
   * @param draft the draft
   * @param rank how far the draft departs from the first choices
   * @param order the position at which the draft was added, which orders drafts of equal rank
   */
  private record Ranked(Draft draft, int rank, int order) {}

  /** The drafts waiting to be completed, lowest rank first and oldest first among equals. */
  private static final class Frontier {
    private final PriorityQueue<Ranked> queue =
        new PriorityQueue<>(Comparator.comparingInt(Ranked::rank).thenComparingInt(Ranked::order));
    private int added;

    void add(Draft draft, int rank) {
      queue.add(new Ranked(draft, rank, added++));
    }

    boolean isEmpty() {
      return queue.isEmpty();
    }

    Ranked next() {
      return queue.remove();
    }
  }

  /**
   * Adds the drafts that complete {@code obligation} in {@code draft}, each ranked {@code rank}
   * plus the position of its completion among the alternatives, and further back when it writes a
   * value the network does not hold.
   */
  private void extend(
      Frontier open, Draft draft, int rank, Verification verification, Obligation obligation) {
    List<List<EditOperator>> completions = complete(draft, verification, obligation);
    for (int position = 0; position < completions.size(); position++) {
      int ranked = rank + position + (unobserved(completions.get(position)) ? MAX_COMPLETIONS : 0);
      extended(draft, completions.get(position))
          .filter(
              extended ->
                  extended.routers().size() <= bounds.maxRouters()
                      && extended.operators().size() <= bounds.maxOperators()
                      && !extended.program().snapshot().equals(draft.program().snapshot()))
          .ifPresent(extended -> open.add(extended, ranked));
    }
  }

  /**
   * Returns whether {@code completion} writes a value that no object of the network holds: a
   * literal the network was searched for, or a fresh name. Such a value departs further from the
   * first choices than any observed one.
   */
  private static boolean unobserved(List<EditOperator> completion) {
    return completion.stream()
        .anyMatch(operator -> operator.provenance().support() == ValueSupport.INVENTED);
  }

  /** Returns the draft extended by {@code completion}, or nothing if it does not apply. */
  private static Optional<Draft> extended(Draft draft, List<EditOperator> completion) {
    try {
      return Optional.of(draft.with(completion));
    } catch (IllegalArgumentException | IllegalStateException inapplicable) {
      return Optional.empty();
    }
  }

  private record Key(String relation, Iterable<ProgramObject.Ref> refs, Requirement requirement) {
    Key {
      Set<ProgramObject.Ref> set = new HashSet<>();
      refs.forEach(set::add);
      refs = Set.copyOf(set);
    }
  }

  /**
   * What a draft still has to satisfy.
   *
   * @param blocking the hard requirements the draft newly violates or was asked to restore, which
   *     it must satisfy before it becomes a hypothesis
   * @param extendable the other violations among the instances the draft affected, which give
   *     alternative hypotheses: soft and newly broken conventional ones, and the hard ones the
   *     draft inherited from the instances it touched, since it re-decided what they depend on
   */
  private record Pending(List<Obligation> blocking, List<Obligation> extendable) {}

  private Pending pending(Draft draft, Set<Key> baseline, Set<Obligation> obligations) {
    Set<ProgramObject.Ref> touched = draft.touched();
    Set<ProgramObject.Ref> targeted = new HashSet<>();
    obligations.forEach(o -> targeted.addAll(o.instance().roles().values()));
    List<Obligation> blocking = new ArrayList<>();
    List<Obligation> extendable = new ArrayList<>();
    for (RelationInstance instance : draft.program().violated()) {
      boolean affected = instance.dependsOn(touched);
      boolean target = instance.roles().values().stream().anyMatch(targeted::contains);
      if (!affected && !target) {
        continue;
      }
      for (Requirement requirement : instance.violated()) {
        boolean old =
            baseline.contains(new Key(instance.relation(), instance.roles().values(), requirement));
        Obligation obligation = new Obligation(instance, requirement);
        boolean hard = requirement.strength() == Requirement.Strength.HARD;
        boolean brokeConvention = requirement.strength() == Requirement.Strength.CONVENTION && !old;
        boolean requested = requested(instance, requirement, obligations);
        if (requested || (hard && (!old || target)) || brokeConvention) {
          blocking.add(obligation);
        } else if (hard || requirement.strength() == Requirement.Strength.SOFT || !old) {
          extendable.add(obligation);
        }
      }
    }
    return new Pending(blocking, extendable);
  }

  /** Returns whether the caller explicitly asked to restore this evolving relation instance. */
  private static boolean requested(
      RelationInstance instance, Requirement requirement, Set<Obligation> obligations) {
    Set<ProgramObject.Ref> roles = new HashSet<>(instance.roles().values());
    for (Obligation obligation : obligations) {
      if (!obligation.instance().relation().equals(instance.relation())
          || !obligation.requirement().equals(requirement)) {
        continue;
      }
      if (obligation.instance().roles().values().stream().anyMatch(roles::contains)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the alternative operator lists that satisfy {@code obligation} in {@code draft}; a
   * symmetric relation is completed in every orientation in which the requirement fails, and a
   * guarded requirement is also satisfied by removing what its guard names.
   */
  List<List<EditOperator>> complete(Draft draft, Verification verification, Obligation obligation) {
    Requirement requirement = obligation.requirement();
    List<Draft> alternatives = List.of(draft);
    for (RelationInstance orientation : orientations(obligation.instance())) {
      List<Draft> completed = new ArrayList<>();
      for (Draft partial : alternatives) {
        if (programs.evaluator().holds(partial.program(), orientation, requirement)) {
          completed.add(partial);
          continue;
        }
        for (List<EditOperator> completion :
            completions(partial, verification, orientation, requirement)) {
          extended(partial, completion).ifPresent(completed::add);
          if (completed.size() >= MAX_COMPLETIONS) {
            break;
          }
        }
        if (completed.size() >= MAX_COMPLETIONS) {
          break;
        }
      }
      alternatives = completed;
      if (alternatives.isEmpty()) {
        return List.of();
      }
    }
    int offset = draft.operators().size();
    return alternatives.stream()
        .map(completed -> completed.operators().subList(offset, completed.operators().size()))
        .distinct()
        .limit(MAX_COMPLETIONS)
        .toList();
  }

  /** Returns the alternative edits that make one orientation satisfy one requirement. */
  private List<List<EditOperator>> completions(
      Draft draft,
      Verification verification,
      RelationInstance orientation,
      Requirement requirement) {
    Completer completer = new Completer(draft, verification, orientation, requirement);
    List<List<EditOperator>> direct =
        switch (requirement.constraint()) {
          case Constraint.Exists exists ->
              exists.path().attributes().isEmpty()
                  ? completer.createRole(exists.path().role())
                  : completer.define(exists.path());
          case Constraint.Absent absent -> completer.unset(absent.path());
          case Constraint.Equal equal -> completer.align(equal.left(), equal.right(), false);
          case Constraint.Agree agree -> completer.align(agree.left(), agree.right(), true);
          case Constraint.Resolves resolves -> completer.resolve(resolves.path());
          case Constraint.Distinct distinct -> completer.distinguish(distinct.path());
        };
    List<List<EditOperator>> alternatives = new ArrayList<>(direct);
    if (requirement.guard().isPresent() && !requirement.guard().get().attributes().isEmpty()) {
      alternatives.addAll(completer.unset(requirement.guard().get()));
    }
    if (direct.isEmpty()
        && requirement.constraint() instanceof Constraint.Exists exists
        && exists.path().attributes().isEmpty()) {
      alternatives.addAll(completer.structuralPrerequisites(exists.path().role()));
    }
    return alternatives;
  }

  private List<RelationInstance> orientations(RelationInstance instance) {
    RelationType relation = schema.relation(instance.relation()).orElseThrow();
    if (!relation.symmetric() || instance.roles().size() != 2) {
      return List.of(instance);
    }
    List<String> names = List.copyOf(instance.roles().keySet());
    Map<String, ProgramObject.Ref> swapped = new LinkedHashMap<>();
    swapped.put(names.get(0), instance.roles().get(names.get(1)));
    swapped.put(names.get(1), instance.roles().get(names.get(0)));
    return List.of(
        instance, new RelationInstance(instance.relation(), swapped, instance.violated()));
  }

  /** Builds the operators that complete one requirement of one instance. */
  private final class Completer {
    private final ProgramModel program;
    private final Set<ProgramObject.Ref> touched;
    private final Verification verification;
    private final RelationInstance instance;
    private final RelationType relation;
    private final Requirement requirement;

    private Completer(
        Draft draft,
        Verification verification,
        RelationInstance instance,
        Requirement requirement) {
      this.program = draft.program();
      this.touched = draft.touched();
      this.verification = verification;
      this.instance = instance;
      this.relation = schema.relation(instance.relation()).orElseThrow();
      this.requirement = requirement;
    }

    private Provenance forced(ValueSupport support) {
      return new Provenance.Forced(relation.name(), requirement, support);
    }

    private Optional<List<String>> valueAt(AttributePath path) {
      return programs.evaluator().valueAt(program, instance, path);
    }

    private Optional<ProgramObject> holderOf(AttributePath path) {
      return programs.evaluator().holderOf(program, instance, path);
    }

    /**
     * Aligns one side of an equality with the other, both ways when both are defined. A value its
     * own object is known to hold wrongly is not copied to the other side: that would carry a fault
     * on rather than repair it.
     */
    List<List<EditOperator>> align(AttributePath left, AttributePath right, boolean agree) {
      Optional<List<String>> l = valueAt(left);
      Optional<List<String>> r = valueAt(right);
      List<List<EditOperator>> alternatives = new ArrayList<>();
      if (r.isPresent() && !disputed(right)) {
        alternatives.addAll(set(left, r.get()));
      }
      if (l.isPresent() && !disputed(left)) {
        alternatives.addAll(set(right, l.get()));
      }
      if (agree && l.isPresent() && r.isEmpty()) {
        alternatives.addAll(unset(left));
      }
      if (agree && r.isPresent() && l.isEmpty()) {
        alternatives.addAll(unset(right));
      }
      return alternatives;
    }

    /**
     * Returns whether the attribute at {@code path} violates a hard equality with another attribute
     * of the same object, as a router's AS that differs from the AS its design assigns it.
     */
    private boolean disputed(AttributePath path) {
      Optional<ProgramObject> holder = holderOf(path);
      if (holder.isEmpty()) {
        return false;
      }
      String name = path.attributes().getLast();
      for (RelationInstance other : program.violated()) {
        for (Requirement violated : other.violated()) {
          if (violated.strength() != Requirement.Strength.HARD
              || !(violated.constraint() instanceof Constraint.Equal equal)) {
            continue;
          }
          Optional<ProgramObject> left =
              programs.evaluator().holderOf(program, other, equal.left());
          Optional<ProgramObject> right =
              programs.evaluator().holderOf(program, other, equal.right());
          if (left.isPresent()
              && left.equals(right)
              && left.equals(holder)
              && (equal.left().attributes().getLast().equals(name)
                  || equal.right().attributes().getLast().equals(name))) {
            return true;
          }
        }
      }
      return false;
    }

    /**
     * Gives the attribute at {@code path} a value: by inserting a statement for it, or, for a
     * derived attribute, by realising a value it could take, which for a reference is an object of
     * the referenced type on the same router, changed ones first, and otherwise a value the
     * attribute has on other objects of the type.
     */
    List<List<EditOperator>> define(AttributePath path) {
      Optional<ProgramObject> holder = holderOf(path);
      if (holder.isEmpty()) {
        return List.of();
      }
      String name = path.attributes().getLast();
      Attribute attribute = attributeOf(holder.get(), name);
      return switch (attribute.source()) {
        case Attribute.Source.Slots slots ->
            insertFor(
                holder.get(),
                slots.slots().stream().map(Attribute.SlotRef::production).toList(),
                Map.of(),
                slots.where());
        case Attribute.Source.Presence presence ->
            insertFor(holder.get(), presence.productions(), Map.of(), presence.where());
        case Attribute.Source.Derived derived -> {
          List<List<EditOperator>> alternatives = new ArrayList<>();
          for (String target : targets(holder.get(), attribute)) {
            alternatives.addAll(realise(holder.get(), derived.function(), target));
            if (alternatives.size() >= MAX_COMPLETIONS) {
              break;
            }
          }
          yield alternatives;
        }
        default -> List.of();
      };
    }

    /** Returns the values a derived attribute of {@code holder} could be given, likeliest first. */
    private List<String> targets(ProgramObject holder, Attribute attribute) {
      List<String> targets = new ArrayList<>();
      if (attribute.type() instanceof Attribute.Type.Flag) {
        return List.of("true");
      }
      if (attribute.type() instanceof Attribute.Type.Reference reference) {
        List<ProgramObject> candidates = new ArrayList<>();
        for (ProgramObject object : program.objectsOf(holder.ref().router())) {
          if (object.ref().type().equals(reference.object())) {
            candidates.add(object);
          }
        }
        candidates.sort(
            Comparator.comparing((ProgramObject object) -> !touched.contains(object.ref())));
        candidates.forEach(object -> targets.add(object.ref().toString()));
        return targets;
      }
      for (ProgramObject other : program.objects(holder.ref().type())) {
        other
            .value(attribute.name())
            .ifPresent(
                value -> {
                  if (!targets.contains(value)) {
                    targets.add(value);
                  }
                });
      }
      return targets;
    }

    /** Makes the reference at {@code path} name an existing object, or creates the named object. */
    List<List<EditOperator>> resolve(AttributePath path) {
      Optional<ProgramObject> holder = holderOf(path);
      if (holder.isEmpty()) {
        return List.of();
      }
      String name = path.attributes().getLast();
      Attribute attribute = attributeOf(holder.get(), name);
      if (!(attribute.type() instanceof Attribute.Type.Reference reference)) {
        return List.of();
      }
      List<List<EditOperator>> alternatives = new ArrayList<>();
      ObjectType type = schema.object(reference.object()).orElseThrow();
      for (String value : holder.get().values(name)) {
        for (ProgramObject candidate : program.objects(type.name())) {
          if (candidate.ref().router().equals(holder.get().ref().router())
              && candidate.ref().key().size() == 1
              && !candidate.ref().key().getFirst().equals(value)) {
            alternatives.addAll(set(path, List.of(candidate.ref().key().getFirst())));
          }
        }
        alternatives.addAll(create(type, holder.get().ref().router(), List.of(value), Map.of()));
      }
      return alternatives;
    }

    /** Creates the object of the unfilled role {@code role}, placed and keyed by the joins. */
    List<List<EditOperator>> createRole(String roleName) {
      RelationType.Role role = relation.role(roleName).orElseThrow();
      ObjectType type = schema.object(role.object()).orElseThrow();
      RouterId router = null;
      Map<String, List<String>> fixed = new LinkedHashMap<>();
      List<List<String>> keyAlternatives = new ArrayList<>();
      for (RelationType.Join join : relation.joins()) {
        AttributePath own;
        AttributePath other;
        if (join.left().role().equals(roleName)) {
          own = join.left();
          other = join.right();
        } else if (join.right().role().equals(roleName)) {
          own = join.right();
          other = join.left();
        } else {
          continue;
        }
        Optional<List<String>> value = valueAt(other);
        if (value.isEmpty() || own.attributes().size() != 1) {
          return List.of();
        }
        String attributeName = own.attributes().getFirst();
        Attribute attribute = type.attribute(attributeName).orElseThrow();
        switch (attribute.source()) {
          case Attribute.Source.Owner _ ->
              router = ProgramObject.Ref.parse(value.get().getFirst()).router();
          case Attribute.Source.KeyComponent _ -> fixed.put(attributeName, value.get());
          case Attribute.Source.Derived derived -> {
            ProgramObject template =
                new ProgramObject(
                    new ProgramObject.Ref(type.name(), routerOr(router, instance), List.of()),
                    Map.of(),
                    List.of());
            for (ProgramModel.Realisation realisation :
                programs
                    .factory()
                    .realise(derived.function(), value.get().getFirst(), template, program)) {
              List<String> key = new ArrayList<>();
              for (String slot : type.key()) {
                if (realisation.values().containsKey(slot)) {
                  key.add(realisation.values().get(slot).getFirst());
                }
              }
              if (key.size() == type.key().size()) {
                keyAlternatives.add(key);
              }
            }
          }
          case Attribute.Source.Slots slots ->
              slots.slots().stream()
                  .findFirst()
                  .ifPresent(ref -> fixed.put(ref.slot(), value.get()));
          default -> {}
        }
      }
      if (router == null) {
        return List.of();
      }
      List<List<EditOperator>> alternatives = new ArrayList<>();
      if (keyAlternatives.isEmpty()) {
        List<String> key = new ArrayList<>();
        for (String slot : type.key()) {
          if (!fixed.containsKey(slot)) {
            return List.of();
          }
          key.add(fixed.get(slot).getFirst());
        }
        return create(type, router, key, fixed);
      }
      for (List<String> key : keyAlternatives) {
        alternatives.addAll(create(type, router, key, fixed));
        if (alternatives.size() >= MAX_COMPLETIONS) {
          break;
        }
      }
      return alternatives;
    }

    /**
     * Opens a missing syntactic context needed to create {@code roleName}. This is the generic
     * prerequisite of creating an object whose defining statements live in a parameterised block:
     * for example, a BGP-neighbor role cannot be created until its router has a BGP process. The
     * values of the opener still come from the ordinary typed domains, and closure then retries the
     * original relation obligation.
     */
    List<List<EditOperator>> structuralPrerequisites(String roleName) {
      Optional<RouterId> placement = roleRouter(roleName);
      if (placement.isEmpty()) {
        return List.of();
      }
      ObjectType role = schema.object(relation.role(roleName).orElseThrow().object()).orElseThrow();
      Set<Grammar.Context> needed = new HashSet<>();
      for (Production source : role.sources()) {
        Grammar.Context context = source.context();
        while (!context.isRoot()) {
          needed.add(context);
          context = schema.grammar().context(context.parent().orElseThrow()).orElseThrow();
        }
      }
      ProgramObject.Ref routerRef =
          new ProgramObject.Ref(ObjectType.ROUTER, placement.get(), List.of());
      Optional<ProgramObject> router = program.object(routerRef);
      if (router.isEmpty()) {
        return List.of();
      }
      List<List<EditOperator>> prerequisites = new ArrayList<>();
      ObjectType routerType = schema.object(ObjectType.ROUTER).orElseThrow();
      for (Attribute attribute : routerType.attributes().values()) {
        if (!(attribute.source() instanceof Attribute.Source.Slots slots)) {
          continue;
        }
        List<Production> openers =
            slots.slots().stream()
                .map(Attribute.SlotRef::production)
                .filter(production -> production.opens().filter(needed::contains).isPresent())
                .toList();
        if (openers.isEmpty()) {
          continue;
        }
        prerequisites.addAll(insertFor(router.get(), openers, Map.of(), slots.where()));
        if (prerequisites.size() >= MAX_COMPLETIONS) {
          return prerequisites.subList(0, MAX_COMPLETIONS);
        }
      }
      return prerequisites;
    }

    /** Returns the router on which an unfilled role must be placed, from its owner join. */
    private Optional<RouterId> roleRouter(String roleName) {
      ObjectType type = schema.object(relation.role(roleName).orElseThrow().object()).orElseThrow();
      for (RelationType.Join join : relation.joins()) {
        AttributePath own;
        AttributePath other;
        if (join.left().role().equals(roleName)) {
          own = join.left();
          other = join.right();
        } else if (join.right().role().equals(roleName)) {
          own = join.right();
          other = join.left();
        } else {
          continue;
        }
        if (own.attributes().size() != 1) {
          continue;
        }
        Attribute attribute = type.attribute(own.attributes().getFirst()).orElse(null);
        if (attribute == null || !(attribute.source() instanceof Attribute.Source.Owner)) {
          continue;
        }
        Optional<List<String>> value = valueAt(other);
        if (value.isPresent() && value.get().size() == 1) {
          try {
            return Optional.of(ProgramObject.Ref.parse(value.get().getFirst()).router());
          } catch (IllegalArgumentException malformed) {
            return Optional.empty();
          }
        }
      }
      return Optional.empty();
    }

    private RouterId routerOr(RouterId router, RelationInstance instance) {
      return router != null ? router : instance.roles().values().iterator().next().router();
    }

    /**
     * Inserts the defining statement of a new object of {@code type} with {@code key} on {@code
     * router}.
     */
    private List<List<EditOperator>> create(
        ObjectType type, RouterId router, List<String> key, Map<String, List<String>> fixed) {
      if (program.object(new ProgramObject.Ref(type.name(), router, key)).isPresent()) {
        return List.of();
      }
      Map<String, List<String>> values = new LinkedHashMap<>(fixed);
      for (int at = 0; at < type.key().size(); at++) {
        values.put(type.key().get(at), List.of(key.get(at)));
      }
      ProgramObject routerObject =
          program.object(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of())).orElse(null);
      if (routerObject == null) {
        return List.of();
      }
      List<List<EditOperator>> alternatives = new ArrayList<>();
      for (Production source : type.sources()) {
        alternatives.addAll(insert(routerObject, source, values));
        if (!alternatives.isEmpty()) {
          break;
        }
      }
      return alternatives;
    }

    /**
     * Gives the attribute at {@code path} a value from its domain that no other instance of the
     * relation has there.
     */
    List<List<EditOperator>> distinguish(AttributePath path) {
      Optional<ProgramObject> holder = holderOf(path);
      if (holder.isEmpty()) {
        return List.of();
      }
      String name = path.attributes().getLast();
      if (!(attributeOf(holder.get(), name).source() instanceof Attribute.Source.Slots slots)
          || slots.slots().isEmpty()) {
        return List.of();
      }
      Set<List<String>> taken = new HashSet<>();
      for (RelationInstance other : program.instances(relation.name())) {
        programs.evaluator().valueAt(program, other, path).ifPresent(taken::add);
      }
      Attribute.SlotRef ref = slots.slots().getFirst();
      Map<String, List<String>> fixed = new LinkedHashMap<>();
      for (Statement statement :
          statementsOf(holder.get(), List.of(ref.production()), slots.where())) {
        Production.Match match = statement.match().orElseThrow();
        match.values().forEach((slot, values) -> fixed.put(slot, values));
      }
      fixed.remove(ref.slot());
      List<List<EditOperator>> alternatives = new ArrayList<>();
      for (SeedGenerator.Filling filling :
          seeds.fillings(
              program,
              verification,
              holder.get().ref().router(),
              ref.production(),
              fixed,
              Optional.of(instance))) {
        List<String> value = filling.map().get(ref.slot());
        if (value == null || taken.contains(value)) {
          continue;
        }
        alternatives.addAll(set(path, value));
        if (alternatives.size() >= MAX_COMPLETIONS) {
          break;
        }
      }
      return alternatives;
    }

    /** Sets the attribute at {@code path} to {@code value}. */
    List<List<EditOperator>> set(AttributePath path, List<String> value) {
      Optional<ProgramObject> holder = holderOf(path);
      if (holder.isEmpty()) {
        return List.of();
      }
      ProgramObject object = holder.get();
      String name = path.attributes().getLast();
      Attribute attribute = attributeOf(object, name);
      return switch (attribute.source()) {
        case Attribute.Source.KeyComponent _ -> rename(object, name, value);
        case Attribute.Source.Slots slots -> {
          List<List<EditOperator>> replaced = replaceSlots(object, slots, value);
          yield replaced.isEmpty()
              ? insertFor(
                  object,
                  slots.slots().stream().map(Attribute.SlotRef::production).toList(),
                  slotFixed(slots, value),
                  slots.where())
              : replaced;
        }
        case Attribute.Source.Presence presence ->
            insertFor(object, presence.productions(), Map.of(), presence.where());
        case Attribute.Source.Derived derived ->
            realise(object, derived.function(), value.getFirst());
        case Attribute.Source.Owner _ -> List.of();
      };
    }

    /** Removes the statements that give the attribute at {@code path} its value. */
    List<List<EditOperator>> unset(AttributePath path) {
      Optional<ProgramObject> holder = holderOf(path);
      if (holder.isEmpty()) {
        return List.of();
      }
      String name = path.attributes().getLast();
      Attribute attribute = attributeOf(holder.get(), name);
      if (attribute.source() instanceof Attribute.Source.Derived derived
          && attribute.type() instanceof Attribute.Type.Flag) {
        return realise(holder.get(), derived.function(), "false");
      }
      List<Production> productions =
          switch (attribute.source()) {
            case Attribute.Source.Slots slots ->
                slots.slots().stream().map(Attribute.SlotRef::production).toList();
            case Attribute.Source.Presence presence -> presence.productions();
            default -> List.of();
          };
      List<Attribute.Condition> where =
          switch (attribute.source()) {
            case Attribute.Source.Slots slots -> slots.where();
            case Attribute.Source.Presence presence -> presence.where();
            default -> List.of();
          };
      List<EditOperator> deletions = new ArrayList<>();
      for (Statement statement : statementsOf(holder.get(), productions, where)) {
        deletions.add(new EditOperator.DeleteStatement(statement));
      }
      return deletions.isEmpty() ? List.of() : List.of(deletions);
    }

    private Map<String, List<String>> slotFixed(Attribute.Source.Slots slots, List<String> value) {
      Map<String, List<String>> fixed = new LinkedHashMap<>();
      slots.slots().stream().findFirst().ifPresent(ref -> fixed.put(ref.slot(), value));
      for (Attribute.Condition condition : slots.where()) {
        fixed.put(condition.slot(), List.of(condition.literal()));
      }
      return fixed;
    }

    private List<List<EditOperator>> replaceSlots(
        ProgramObject object, Attribute.Source.Slots slots, List<String> value) {
      List<EditOperator> operators = new ArrayList<>();
      for (Statement statement :
          statementsOf(
              object,
              slots.slots().stream().map(Attribute.SlotRef::production).toList(),
              slots.where())) {
        Production production = statement.match().orElseThrow().production();
        for (Attribute.SlotRef ref : slots.slots()) {
          if (ref.production().equals(production)) {
            operators.add(
                new EditOperator.ReplaceSlots(
                    statement, ref.slot(), value, forced(ValueSupport.OBSERVED)));
            break;
          }
        }
      }
      return operators.isEmpty() ? List.of() : List.of(operators);
    }

    private List<List<EditOperator>> rename(ProgramObject object, String slot, List<String> value) {
      List<EditOperator> operators = new ArrayList<>();
      for (Statement statement : statementsOf(object, List.of(), List.of())) {
        if (statement.match().filter(m -> m.production().slot(slot).isPresent()).isPresent()) {
          operators.add(
              new EditOperator.ReplaceSlots(statement, slot, value, forced(ValueSupport.OBSERVED)));
        }
      }
      return operators.isEmpty() ? List.of() : List.of(operators);
    }

    private List<List<EditOperator>> realise(ProgramObject object, String function, String target) {
      List<List<EditOperator>> alternatives = new ArrayList<>();
      for (ProgramModel.Realisation realisation :
          programs.factory().realise(function, target, object, program)) {
        ProgramObject holder = realisation.holder().flatMap(program::object).orElse(object);
        List<Statement> existing =
            realisation.existing() == ProgramModel.Realisation.Existing.KEEP
                ? List.of()
                : realisation.statement().isPresent()
                    ? program.statements(holder.ref().router()).stream()
                        .filter(s -> s.span().equals(realisation.statement().get()))
                        .toList()
                    : statementsOf(holder, List.of(realisation.production()), List.of());
        if (existing.isEmpty()) {
          alternatives.addAll(insert(holder, realisation.production(), realisation.values()));
        } else {
          List<EditOperator> operators = new ArrayList<>();
          for (Statement statement : existing) {
            operators.add(
                new EditOperator.ReplaceSlots(
                    statement, realisation.values(), forced(ValueSupport.OBSERVED)));
          }
          alternatives.add(operators);
        }
        if (alternatives.size() >= MAX_COMPLETIONS) {
          break;
        }
      }
      return alternatives;
    }

    /** Inserts a statement of one of {@code productions} bound to {@code object}. */
    private List<List<EditOperator>> insertFor(
        ProgramObject object,
        List<Production> productions,
        Map<String, List<String>> fixed,
        List<Attribute.Condition> where) {
      Map<String, List<String>> values = new LinkedHashMap<>(fixed);
      for (Attribute.Condition condition : where) {
        values.putIfAbsent(condition.slot(), List.of(condition.literal()));
      }
      List<List<EditOperator>> alternatives = new ArrayList<>();
      for (Production production : productions) {
        alternatives.addAll(insert(object, production, values));
        if (!alternatives.isEmpty()) {
          break;
        }
      }
      return alternatives;
    }

    /** Inserts a statement of {@code production} for {@code object}, fixing its key slots. */
    private List<List<EditOperator>> insert(
        ProgramObject object, Production production, Map<String, List<String>> fixed) {
      ObjectType type = schema.object(object.ref().type()).orElseThrow();
      Map<String, List<String>> values = new LinkedHashMap<>(fixed);
      for (int at = 0; at < type.key().size(); at++) {
        if (production.slot(type.key().get(at)).isPresent()) {
          values.putIfAbsent(type.key().get(at), List.of(object.ref().key().get(at)));
        }
      }
      for (String slot : values.keySet()) {
        if (production.slot(slot).isEmpty()) {
          return List.of();
        }
      }
      Layout layout =
          new Layout(
              program.statements(object.ref().router()),
              program.snapshot().text(object.ref().router()).lineCount());
      List<Statement> bound = statementsOf(object, List.of(), List.of());
      Optional<SeedGenerator.Anchor> anchor =
          seeds.anchor(layout, type, bound, production.context());
      if (anchor.isEmpty()) {
        return List.of();
      }
      List<List<EditOperator>> alternatives = new ArrayList<>();
      for (SeedGenerator.Filling filling :
          seeds.fillings(
              program,
              verification,
              object.ref().router(),
              production,
              values,
              Optional.of(instance))) {
        List<EditOperator> operators = new ArrayList<>(anchor.get().openers());
        operators.add(
            new EditOperator.InsertStatement(
                object.ref().router(),
                anchor.get().after(),
                anchor.get().indentation(),
                production,
                filling.map(),
                forced(filling.provenance().support())));
        operators.addAll(anchor.get().closers());
        alternatives.add(operators);
        if (alternatives.size() >= MAX_COMPLETIONS) {
          break;
        }
      }
      return alternatives;
    }

    /**
     * Returns the bound statements of {@code object} of {@code productions} meeting {@code where}.
     */
    private List<Statement> statementsOf(
        ProgramObject object, List<Production> productions, List<Attribute.Condition> where) {
      Layout layout =
          new Layout(
              program.statements(object.ref().router()),
              program.snapshot().text(object.ref().router()).lineCount());
      List<Statement> statements = new ArrayList<>();
      for (var span : object.statements()) {
        Statement statement = layout.at(span.first()).orElse(null);
        if (statement == null) {
          continue;
        }
        Optional<Production.Match> match = statement.match();
        if (productions.isEmpty()
            || (match.isPresent()
                && productions.contains(match.get().production())
                && meets(match.get(), where))) {
          statements.add(statement);
        }
      }
      return statements;
    }

    private Attribute attributeOf(ProgramObject object, String name) {
      return schema.object(object.ref().type()).flatMap(type -> type.attribute(name)).orElseThrow();
    }
  }

  private static boolean meets(Production.Match match, List<Attribute.Condition> conditions) {
    for (Attribute.Condition condition : conditions) {
      if (!match.values(condition.slot()).equals(List.of(condition.literal()))) {
        return false;
      }
    }
    return true;
  }
}
