package se.kth.sonar.program;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

/**
 * Turns parsed statements into typed objects as the relation schema declares them.
 *
 * <p>Each statement of a source production defines an object, identified by its router and key slot
 * values; statements of several sources with the same key define one object. A statement binds to
 * the objects it defines, else to the object that its {@code by} slots or its key-named slots name
 * on the same router, else to the objects whose block encloses it, else to the router. A statement
 * that names an object which does not exist is dangling and binds to the router. Attribute values
 * are read from the bound statements that meet the attribute's conditions. Derived attributes are
 * left to {@link ProgramModel.Factory#derive}.
 */
public final class ObjectBinder {
  private final RelationSchema schema;
  private final Map<Production, List<ObjectType>> definers = new HashMap<>();
  private final Map<Production, List<Naming>> namings = new HashMap<>();

  /**
   * The result of binding.
   *
   * @param objects the objects in router and line order, each router's own object first
   * @param dangling the statements that name a missing object, in router and line order
   */
  public record Bound(List<ProgramObject> objects, List<SourceSpan> dangling) {
    /** Copies the lists. */
    public Bound {
      objects = List.copyOf(objects);
      dangling = List.copyOf(dangling);
    }
  }

  private record Naming(ObjectType type, List<String> slots) {}

  private record Named(boolean applies, List<Builder> owners) {}

  /** Creates a binder for {@code schema}. */
  public ObjectBinder(RelationSchema schema) {
    this.schema = Objects.requireNonNull(schema, "schema");
    for (ObjectType type : schema.objects()) {
      for (Production source : type.sources()) {
        definers.computeIfAbsent(source, production -> new ArrayList<>()).add(type);
      }
    }
    for (ObjectType type : schema.objects()) {
      if (type.isRouter()) {
        continue;
      }
      for (Attribute attribute : type.attributes().values()) {
        List<String> by = List.of();
        List<Production> productions = List.of();
        switch (attribute.source()) {
          case Attribute.Source.Slots slots -> {
            by = slots.by();
            productions = slots.slots().stream().map(Attribute.SlotRef::production).toList();
          }
          case Attribute.Source.Presence presence -> {
            by = presence.by();
            productions = presence.productions();
          }
          default -> {}
        }
        for (Production production : productions) {
          if (definers.getOrDefault(production, List.of()).contains(type)) {
            continue;
          }
          List<String> slots = by.isEmpty() ? type.key() : by;
          if (slots.stream().allMatch(slot -> production.slot(slot).isPresent())) {
            List<Naming> list = namings.computeIfAbsent(production, p -> new ArrayList<>());
            if (list.stream().noneMatch(naming -> naming.type().equals(type))) {
              list.add(new Naming(type, slots));
            }
          }
        }
      }
    }
  }

  /** Returns the objects defined by the statements of every router. */
  public Bound bind(Map<RouterId, List<Statement>> statements) {
    List<ProgramObject> objects = new ArrayList<>();
    List<SourceSpan> dangling = new ArrayList<>();
    statements.forEach(
        (router, list) -> {
          Bound bound = bind(router, list);
          objects.addAll(bound.objects());
          dangling.addAll(bound.dangling());
        });
    return new Bound(objects, dangling);
  }

  /** Returns the objects defined by the statements of one router, the router's own object first. */
  public Bound bind(RouterId router, List<Statement> statements) {
    List<ProgramObject> objects = new ArrayList<>();
    List<SourceSpan> dangling = new ArrayList<>();
    bindRouter(router, statements, objects, dangling);
    return new Bound(objects, dangling);
  }

  private void bindRouter(
      RouterId router,
      List<Statement> statements,
      List<ProgramObject> objects,
      List<SourceSpan> dangling) {
    SequencedMap<ProgramObject.Ref, Builder> builders = new LinkedHashMap<>();
    Builder routerObject = new Builder(new ProgramObject.Ref(ObjectType.ROUTER, router, List.of()));
    builders.put(routerObject.ref, routerObject);
    Map<Integer, Statement> byLine = new HashMap<>();
    Map<Integer, List<Builder>> defined = new HashMap<>();
    for (Statement statement : statements) {
      byLine.put(statement.span().first(), statement);
      List<Builder> owners = new ArrayList<>();
      for (ObjectType type :
          statement
              .match()
              .map(m -> definers.getOrDefault(m.production(), List.of()))
              .orElse(List.of())) {
        Production.Match match = statement.match().orElseThrow();
        List<String> key = new ArrayList<>(type.key().size());
        for (String slot : type.key()) {
          key.add(match.value(slot));
        }
        ProgramObject.Ref ref = new ProgramObject.Ref(type.name(), router, key);
        owners.add(builders.computeIfAbsent(ref, Builder::new));
      }
      if (!owners.isEmpty()) {
        defined.put(statement.span().first(), owners);
      }
    }
    for (Statement statement : statements) {
      List<Builder> owners = defined.get(statement.span().first());
      if (owners == null) {
        Named named = named(router, statement, builders);
        if (named.applies() && named.owners().isEmpty()) {
          dangling.add(statement.span());
        }
        owners = named.applies() ? named.owners() : enclosing(statement, byLine, defined);
        if (owners.isEmpty()) {
          owners = List.of(routerObject);
        }
      }
      for (Builder owner : owners) {
        owner.bound.add(statement);
      }
    }
    for (Builder builder : builders.values()) {
      objects.add(builder.build(schema.object(builder.ref.type()).orElseThrow()));
    }
  }

  private Named named(
      RouterId router, Statement statement, Map<ProgramObject.Ref, Builder> builders) {
    Optional<Production.Match> match = statement.match();
    List<Naming> candidates =
        match.map(m -> namings.getOrDefault(m.production(), List.<Naming>of())).orElse(List.of());
    if (candidates.isEmpty()) {
      return new Named(false, List.of());
    }
    List<Builder> owners = new ArrayList<>(1);
    for (Naming naming : candidates) {
      List<String> key = new ArrayList<>(naming.slots().size());
      for (String slot : naming.slots()) {
        key.add(match.orElseThrow().value(slot));
      }
      Builder owner = builders.get(new ProgramObject.Ref(naming.type().name(), router, key));
      if (owner != null) {
        owners.add(owner);
      }
    }
    return new Named(true, owners);
  }

  private static List<Builder> enclosing(
      Statement statement, Map<Integer, Statement> byLine, Map<Integer, List<Builder>> defined) {
    int opener = statement.opener();
    while (opener > 0) {
      List<Builder> owners = defined.get(opener);
      if (owners != null) {
        return owners;
      }
      Statement above = byLine.get(opener);
      opener = above == null ? 0 : above.opener();
    }
    return List.of();
  }

  private static final class Builder {
    private final ProgramObject.Ref ref;
    private final List<Statement> bound = new ArrayList<>();

    private Builder(ProgramObject.Ref ref) {
      this.ref = ref;
    }

    private ProgramObject build(ObjectType type) {
      SequencedMap<String, List<String>> attributes = new LinkedHashMap<>();
      int component = 0;
      for (Attribute attribute : type.attributes().values()) {
        List<String> values =
            switch (attribute.source()) {
              case Attribute.Source.KeyComponent _ -> List.of(ref.key().get(component++));
              case Attribute.Source.Owner _ ->
                  List.of(
                      new ProgramObject.Ref(ObjectType.ROUTER, ref.router(), List.of()).toString());
              case Attribute.Source.Slots slots -> slotValues(slots, attribute.many());
              case Attribute.Source.Presence presence -> presence(presence);
              case Attribute.Source.Derived _ -> List.of();
            };
        if (!values.isEmpty()) {
          attributes.put(attribute.name(), values);
        }
      }
      List<SourceSpan> spans = bound.stream().map(Statement::span).toList();
      return new ProgramObject(ref, attributes, spans);
    }

    /**
     * Reads the slot values of the bound statements. A single-valued attribute takes the value of
     * the last statement, as the device does when a statement is repeated.
     */
    private List<String> slotValues(Attribute.Source.Slots source, boolean many) {
      List<String> values = new ArrayList<>();
      for (Statement statement : bound) {
        Production.Match match = statement.match().orElse(null);
        if (match == null) {
          continue;
        }
        for (Attribute.SlotRef ref : source.slots()) {
          if (ref.production().equals(match.production()) && meets(match, source.where())) {
            if (!many) {
              values.clear();
            }
            values.addAll(match.values(ref.slot()));
          }
        }
      }
      return Collections.unmodifiableList(values);
    }

    private List<String> presence(Attribute.Source.Presence source) {
      for (Statement statement : bound) {
        Production.Match match = statement.match().orElse(null);
        if (match != null
            && source.productions().contains(match.production())
            && meets(match, source.where())) {
          return List.of("true");
        }
      }
      return List.of();
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
}
