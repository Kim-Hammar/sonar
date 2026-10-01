package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.grammar.Symbol;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.relation.RelationSchema;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.Verification;

/**
 * Proposes the first operators of every candidate at a site: deleting one of the site's statements
 * or the whole object, replacing slot values with values from the slots' domains, completing a
 * statement the grammar could not match, or inserting a statement that the site's context allows
 * and that the network uses somewhere, with values from the domains. A block object also owns its
 * grammar-matched contents, so an ACL site can edit an ACL entry without introducing a separate,
 * dialect-specific object type. A seed is a short list of operators; a statement inserted into a
 * block that does not exist yet comes with the block's opener and closer.
 */
public final class SeedGenerator {
  private final Grammar grammar;
  private final RelationSchema schema;
  private final ValueDomains domains;
  private final Bounds bounds;
  private final Set<Production> inert = new HashSet<>();
  private final Set<Grammar.Context> foreign = new HashSet<>();

  /** Creates a generator over the grammar of {@code schema}. */
  public SeedGenerator(RelationSchema schema, ValueDomains domains, Bounds bounds) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.grammar = schema.grammar();
    this.domains = Objects.requireNonNull(domains, "domains");
    this.bounds = Objects.requireNonNull(bounds, "bounds");
    for (ObjectType type : schema.objects()) {
      if (!type.isRouter()) {
        for (Production source : type.sources()) {
          source.opens().ifPresent(foreign::add);
        }
      }
    }
    Set<Production> read = new HashSet<>();
    for (ObjectType type : schema.objects()) {
      read.addAll(type.sources());
      if (!type.isRouter()) {
        type.sources().stream()
            .map(Production::opens)
            .flatMap(Optional::stream)
            .forEach(context -> read.addAll(grammar.productions(context)));
      }
      for (Attribute attribute : type.attributes().values()) {
        switch (attribute.source()) {
          case Attribute.Source.Slots slots ->
              slots.slots().forEach(ref -> read.add(ref.production()));
          case Attribute.Source.Presence presence -> read.addAll(presence.productions());
          default -> {}
        }
      }
    }
    for (Production production : grammar.productions()) {
      if (!read.contains(production) && production.opens().isEmpty()) {
        inert.add(production);
      }
    }
  }

  /** Returns whether changing a statement of {@code production} can affect the program. */
  public boolean inert(Production production) {
    return inert.contains(production);
  }

  /** Returns the seeds at {@code site}, each a short list of operators. */
  public List<List<EditOperator>> seeds(
      ProgramModel program, Verification verification, ProgramObject.Ref site) {
    ProgramObject object = program.object(site).orElse(null);
    if (object == null) {
      return List.of();
    }
    RouterId router = site.router();
    Layout layout =
        new Layout(program.statements(router), program.snapshot().text(router).lineCount());
    ObjectType type = schema.object(site.type()).orElseThrow();
    List<Statement> bound =
        object.statements().stream().map(span -> layout.at(span.first()).orElseThrow()).toList();
    List<List<EditOperator>> seeds = new ArrayList<>();
    for (Statement statement : editable(layout, type, bound)) {
      seeds.addAll(edits(program, verification, layout, type, bound, statement));
    }
    if (!type.isRouter() && bound.size() > 1) {
      seeds.add(deleteAll(layout, bound));
    }
    seeds.addAll(insertions(program, verification, layout, type, object, bound));
    return seeds;
  }

  /** Returns an object's defining statements and the matched contents of a non-router block. */
  private static List<Statement> editable(Layout layout, ObjectType type, List<Statement> bound) {
    List<Statement> editable = new ArrayList<>(bound);
    if (type.isRouter()) {
      return editable;
    }
    for (Statement opener : bound) {
      if (opener.match().flatMap(match -> match.production().opens()).isEmpty()) {
        continue;
      }
      layout.statements().stream()
          .filter(statement -> statement.match().isPresent())
          .filter(statement -> layout.encloses(opener.span().first(), statement))
          .filter(statement -> !editable.contains(statement))
          .forEach(editable::add);
    }
    return editable;
  }

  /**
   * Returns the typed operators that insert one fully specified statement near {@code template}.
   *
   * <p>The template supplies only the syntactic block and insertion position. The production and
   * values define the new object, so this also materialises an inferred relation member that does
   * not exist yet.
   */
  public List<EditOperator> insert(
      ProgramModel program,
      ProgramObject.Ref template,
      String productionId,
      Map<String, List<String>> values,
      Provenance provenance) {
    ProgramObject object = program.object(template).orElse(null);
    if (object == null) {
      return List.of();
    }
    Production production = grammar.production(productionId).orElseThrow();
    Layout layout =
        new Layout(
            program.statements(template.router()),
            program.snapshot().text(template.router()).lineCount());
    ObjectType type = schema.object(template.type()).orElseThrow();
    List<Statement> bound =
        object.statements().stream().map(span -> layout.at(span.first()).orElseThrow()).toList();
    Optional<Anchor> anchor = anchor(layout, type, bound, production.context());
    if (anchor.isEmpty()) {
      return List.of();
    }
    List<EditOperator> operators = new ArrayList<>(anchor.get().openers());
    operators.add(
        new EditOperator.InsertStatement(
            template.router(),
            anchor.get().after(),
            anchor.get().indentation(),
            production,
            values,
            provenance));
    operators.addAll(anchor.get().closers());
    return List.copyOf(operators);
  }

  private List<List<EditOperator>> edits(
      ProgramModel program,
      Verification verification,
      Layout layout,
      ObjectType type,
      List<Statement> bound,
      Statement statement) {
    List<List<EditOperator>> seeds = new ArrayList<>();
    Optional<Production.Match> match = statement.match();
    if (match.isEmpty()) {
      seeds.addAll(completions(program, verification, statement));
      seeds.add(List.of(new EditOperator.DeleteStatement(statement)));
      return seeds;
    }
    Production production = match.get().production();
    if (inert(production) || production.closes()) {
      return seeds;
    }
    seeds.add(
        List.of(new EditOperator.DeleteStatement(statement, layout.end(statement.span().first()))));
    RouterId router = statement.span().router();
    for (List<String> group : domains.groups(production)) {
      boolean renames = group.stream().anyMatch(type.key()::contains);
      List<String> current = new ArrayList<>();
      group.forEach(slot -> current.addAll(match.get().values(slot)));
      for (ValueDomains.Candidate candidate :
          domains.candidates(program, verification, router, production, group, Optional.empty())) {
        if (candidate.values().equals(current)) {
          continue;
        }
        Map<String, List<String>> values = zip(group, candidate.values());
        if (renames) {
          List<EditOperator> rename = new ArrayList<>();
          for (Statement sibling : bound) {
            Optional<Production.Match> siblingMatch = sibling.match();
            if (siblingMatch.isPresent()
                && group.stream()
                    .allMatch(slot -> siblingMatch.get().production().slot(slot).isPresent())
                && siblingMatch
                    .get()
                    .values(group.getFirst())
                    .equals(match.get().values(group.getFirst()))) {
              rename.add(new EditOperator.ReplaceSlots(sibling, values, candidate.provenance()));
            }
          }
          seeds.add(rename);
        } else {
          seeds.add(
              List.of(new EditOperator.ReplaceSlots(statement, values, candidate.provenance())));
        }
      }
    }
    return seeds;
  }

  /** Completes an unmatched statement into each production its tokens begin. */
  private List<List<EditOperator>> completions(
      ProgramModel program, Verification verification, Statement statement) {
    List<String> tokens = Grammar.tokens(statement.text());
    List<List<EditOperator>> seeds = new ArrayList<>();
    for (Production production : grammar.productions(statement.context())) {
      if (inert(production)
          || production.variableLength()
          || tokens.size() >= production.minLength()) {
        continue;
      }
      Map<String, List<String>> fixed = new LinkedHashMap<>();
      boolean prefix = true;
      for (int at = 0; at < tokens.size() && prefix; at++) {
        Symbol symbol = production.symbols().get(at);
        prefix = symbol.accepts(tokens.get(at));
        if (prefix && symbol instanceof Symbol.Slot slot) {
          fixed.put(slot.name(), List.of(tokens.get(at)));
        }
      }
      if (!prefix) {
        continue;
      }
      for (Filling values :
          fillings(
              program,
              verification,
              statement.span().router(),
              production,
              fixed,
              Optional.empty())) {
        seeds.add(
            List.of(
                new EditOperator.ReplaceStatement(
                    statement, production, values.map(), values.provenance())));
      }
    }
    return seeds;
  }

  private static List<EditOperator> deleteAll(Layout layout, List<Statement> bound) {
    List<EditOperator> operators = new ArrayList<>();
    for (Statement statement : bound.reversed()) {
      operators.add(
          new EditOperator.DeleteStatement(statement, layout.end(statement.span().first())));
    }
    return operators;
  }

  /** Returns insertions of statements the site's contexts allow but do not have. */
  private List<List<EditOperator>> insertions(
      ProgramModel program,
      Verification verification,
      Layout layout,
      ObjectType type,
      ProgramObject object,
      List<Statement> bound) {
    List<List<EditOperator>> seeds = new ArrayList<>();
    RouterId router = object.ref().router();
    Set<Production> present = new HashSet<>();
    bound.forEach(s -> s.match().map(Production.Match::production).ifPresent(present::add));
    Set<Production> vocabulary = vocabulary(program);
    for (Production production : grammar.productions()) {
      if (inert(production) || production.closes() || production.opens().isPresent()) {
        continue;
      }
      if (!vocabulary.contains(production)
          || (present.contains(production) && !repeatable(production))) {
        continue;
      }
      Map<String, List<String>> fixed = new LinkedHashMap<>();
      if (type.isRouter() && foreign.contains(production.context())) {
        continue;
      }
      if (!type.isRouter()) {
        if (!names(type, production)) {
          continue;
        }
        for (int at = 0; at < type.key().size(); at++) {
          if (production.slot(type.key().get(at)).isEmpty()) {
            break;
          }
          fixed.put(type.key().get(at), List.of(object.ref().key().get(at)));
        }
      }
      Optional<Anchor> anchor = anchor(layout, type, bound, production.context());
      if (anchor.isEmpty()) {
        continue;
      }
      for (Filling filling :
          fillings(program, verification, router, production, fixed, Optional.empty())) {
        List<EditOperator> seed = new ArrayList<>(anchor.get().openers());
        seed.add(
            new EditOperator.InsertStatement(
                router,
                anchor.get().after(),
                anchor.get().indentation(),
                production,
                filling.map(),
                filling.provenance()));
        seed.addAll(anchor.get().closers());
        seeds.add(seed);
      }
    }
    return seeds;
  }

  /**
   * Returns the productions that some statement of the network matches: the kinds of statement the
   * network's design uses, which are the only kinds worth inserting.
   */
  private static Set<Production> vocabulary(ProgramModel program) {
    Set<Production> used = new HashSet<>();
    for (RouterId router : program.snapshot().routers()) {
      for (Statement statement : program.statements(router)) {
        statement.match().ifPresent(match -> used.add(match.production()));
      }
    }
    return used;
  }

  /** Returns whether a statement of {@code production} defines or names objects of {@code type}. */
  private boolean names(ObjectType type, Production production) {
    if (type.sources().contains(production)) {
      return true;
    }
    for (Attribute attribute : type.attributes().values()) {
      List<Production> productions =
          switch (attribute.source()) {
            case Attribute.Source.Slots slots ->
                slots.slots().stream().map(Attribute.SlotRef::production).toList();
            case Attribute.Source.Presence presence -> presence.productions();
            default -> List.of();
          };
      if (productions.contains(production)) {
        return true;
      }
    }
    return false;
  }

  private boolean repeatable(Production production) {
    Set<List<Attribute.Condition>> discriminatorCases = new HashSet<>();
    for (ObjectType type : schema.objects()) {
      for (Attribute attribute : type.attributes().values()) {
        if (attribute.source() instanceof Attribute.Source.Slots slots
            && slots.slots().stream().anyMatch(ref -> ref.production().equals(production))) {
          if (attribute.many()) {
            return true;
          }
          if (!slots.where().isEmpty()) {
            discriminatorCases.add(slots.where());
          }
        }
      }
    }
    return discriminatorCases.size() > 1;
  }

  /**
   * Where a statement of a context goes for a site: after a line, with an indentation, and with the
   * openers and closers of blocks that must be created first.
   */
  record Anchor(
      int after, String indentation, List<EditOperator> openers, List<EditOperator> closers) {}

  /**
   * Returns where to insert a statement of {@code context} for the site, creating blocks as needed.
   */
  Optional<Anchor> anchor(
      Layout layout, ObjectType type, List<Statement> bound, Grammar.Context context) {
    if (context.isRoot()) {
      return Optional.of(new Anchor(layout.last(), "", List.of(), List.of()));
    }
    for (Statement statement : bound.reversed()) {
      if (statement.context().equals(context)
          && statement.match().map(match -> !match.production().closes()).orElse(true)) {
        return Optional.of(
            new Anchor(
                layout.end(statement.span().first()),
                statement.indentation(),
                List.of(),
                List.of()));
      }
    }
    for (Statement statement : bound) {
      Optional<Grammar.Context> opened = statement.match().flatMap(m -> m.production().opens());
      if (opened.filter(context::equals).isPresent()) {
        int line = statement.span().first();
        int after = line;
        for (Statement child : layout.inside(line)) {
          if (child.match().map(match -> !match.production().closes()).orElse(true)) {
            after = child.span().last();
          }
        }
        return Optional.of(new Anchor(after, layout.indentationInside(line), List.of(), List.of()));
      }
    }
    return blockOf(layout, type, bound, context);
  }

  /** Finds or creates the block of {@code context} on the router of the site. */
  private Optional<Anchor> blockOf(
      Layout layout, ObjectType type, List<Statement> bound, Grammar.Context context) {
    Grammar.Context parent = grammar.context(context.parent().orElseThrow()).orElseThrow();
    Optional<Anchor> parentAnchor = anchor(layout, type, bound, parent);
    if (parentAnchor.isEmpty()) {
      return Optional.empty();
    }
    if (parentAnchor.get().openers().isEmpty()) {
      int parentOpener =
          parent.isRoot() ? 0 : enclosingOpener(layout, parentAnchor.get().after(), parent);
      Optional<Statement> existing =
          parentOpener < 0 ? Optional.empty() : layout.opener(context, parentOpener);
      if (existing.isPresent()) {
        int line = existing.get().span().first();
        return Optional.of(
            new Anchor(layout.end(line), layout.indentationInside(line), List.of(), List.of()));
      }
    }
    Optional<Production> opener =
        grammar.productions(parent).stream()
            .filter(p -> p.opens().filter(context::equals).isPresent() && p.slots().isEmpty())
            .findFirst();
    if (opener.isEmpty()) {
      return Optional.empty();
    }
    Optional<Production> closer =
        grammar.productions(context).stream().filter(Production::closes).findFirst();
    RouterId router = bound.isEmpty() ? null : bound.getFirst().span().router();
    if (router == null) {
      return Optional.empty();
    }
    List<EditOperator> openers = new ArrayList<>(parentAnchor.get().openers());
    String indentation = parentAnchor.get().indentation();
    openers.add(
        new EditOperator.InsertStatement(
            router,
            parentAnchor.get().after(),
            indentation,
            opener.get(),
            Map.of(),
            new Provenance.Existing()));
    List<EditOperator> closers = new ArrayList<>();
    closer.ifPresent(
        c ->
            closers.add(
                new EditOperator.InsertStatement(
                    router,
                    parentAnchor.get().after(),
                    indentation,
                    c,
                    Map.of(),
                    new Provenance.Existing())));
    closers.addAll(parentAnchor.get().closers());
    return Optional.of(new Anchor(parentAnchor.get().after(), indentation + " ", openers, closers));
  }

  /** Returns the line that opens the block of {@code context} enclosing line {@code line}. */
  private static int enclosingOpener(Layout layout, int line, Grammar.Context context) {
    Optional<Statement> statement = layout.at(line);
    while (statement.isPresent()) {
      if (statement.get().context().equals(context)) {
        return statement.get().opener();
      }
      Optional<Grammar.Context> opened =
          statement.get().match().flatMap(m -> m.production().opens());
      if (opened.filter(context::equals).isPresent()) {
        return statement.get().span().first();
      }
      statement = layout.at(statement.get().opener());
    }
    return -1;
  }

  /**
   * A complete assignment of a production's slots.
   *
   * @param map the values by slot
   * @param provenance the provenance of the values, invented if any value is
   */
  record Filling(Map<String, List<String>> map, Provenance provenance) {}

  /** Returns complete slot assignments of {@code production} around the {@code fixed} values. */
  List<Filling> fillings(
      ProgramModel program,
      Verification verification,
      RouterId router,
      Production production,
      Map<String, List<String>> fixed,
      Optional<se.kth.sonar.program.RelationInstance> completing) {
    List<Filling> fillings = new ArrayList<>();
    fillings.add(new Filling(new LinkedHashMap<>(fixed), new Provenance.Existing()));
    Set<String> covered = new HashSet<>(fixed.keySet());
    for (List<String> group : domains.groups(production)) {
      if (group.stream().anyMatch(covered::contains)) {
        continue;
      }
      List<ValueDomains.Candidate> candidates =
          domains.candidates(program, verification, router, production, group, completing);
      if (candidates.isEmpty()) {
        return List.of();
      }
      List<Filling> extended = new ArrayList<>();
      for (Filling filling : fillings) {
        for (ValueDomains.Candidate candidate : candidates) {
          Map<String, List<String>> values = new LinkedHashMap<>(filling.map());
          values.putAll(zip(group, candidate.values()));
          extended.add(new Filling(values, weaker(filling.provenance(), candidate.provenance())));
          if (extended.size() >= bounds.maxValuesPerSlot()) {
            break;
          }
        }
        if (extended.size() >= bounds.maxValuesPerSlot()) {
          break;
        }
      }
      fillings = extended;
      covered.addAll(group);
    }
    for (Symbol.Slot slot : production.slots()) {
      if (!covered.contains(slot.name())) {
        return List.of();
      }
    }
    return fillings;
  }

  private static Provenance weaker(Provenance current, Provenance candidate) {
    if (current instanceof Provenance.Existing) {
      return candidate;
    }
    return candidate.support().compareTo(current.support()) > 0 ? candidate : current;
  }

  private static Map<String, List<String>> zip(List<String> slots, List<String> values) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    for (int at = 0; at < slots.size(); at++) {
      map.put(slots.get(at), List.of(values.get(at)));
    }
    return map;
  }
}
