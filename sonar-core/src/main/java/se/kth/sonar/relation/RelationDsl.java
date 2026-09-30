package se.kth.sonar.relation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.regex.Pattern;
import se.kth.sonar.common.DslException;
import se.kth.sonar.common.DslLine;
import se.kth.sonar.common.Resources;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Lexeme;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Symbol;
import se.kth.sonar.grammar.ValueSort;

/**
 * Reads relation schemas written in the relation language against a grammar.
 *
 * <p>A schema source is line based; blank lines and lines starting with {@code #} are ignored.
 * {@code key} and {@code attribute} lines belong to the preceding {@code object}, and {@code role},
 * {@code join} and {@code require} lines to the preceding {@code relation}.
 *
 * <pre>
 * relations cisco-ios
 * object router
 *   attribute bgp-asn = router-bgp.asn
 * object bgp-neighbor
 *   key peer from neighbor-remote-as
 *   attribute remote-as = neighbor-remote-as.asn
 *   attribute peer-router derived address-owner ref router
 * relation bgp-session symmetric
 *   role local bgp-neighbor each
 *   role remote bgp-neighbor optional
 *   join remote.router = local.peer-router
 *   join local.router = remote.peer-router
 *   require hard exists remote
 *   require hard equal local.remote-as remote.router.bgp-asn
 * domain neighbor-remote-as asn related bgp-session remote.router.bgp-asn
 * </pre>
 *
 * <p>An attribute reads slot values ({@code = production.slot}), references another object by its
 * key ({@code ref object = production.slot}), records whether a statement is present ({@code flag
 * production}), or is derived by the program model ({@code derived function sort}, {@code derived
 * function ref object}, or {@code derived function flag}). Statements inside an object's block bind
 * to that object; statements elsewhere bind by their slots named like the key, or by the slots
 * listed after {@code by}. Conditions {@code where slot = literal} filter statements. Constraints
 * are {@code exists}, {@code equal}, {@code agree}, {@code distinct} and {@code resolves},
 * optionally guarded with {@code when path}. Domain origins are {@code values object.attribute in
 * scope}, {@code names object in scope}, {@code related relation path}, {@code verifier
 * observation}, {@code fresh} and {@code literals}.
 */
public final class RelationDsl {
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
  private static final Pattern COMMA = Pattern.compile(",");
  private static final String ROUTER_ATTRIBUTE = "router";

  private RelationDsl() {}

  /**
   * Parses the schema in {@code source} and validates it against {@code grammar}.
   *
   * @param source the schema text
   * @param origin where the text came from, used in error messages
   * @param grammar the grammar the schema is written against
   * @throws DslException if the source is malformed or the schema is invalid
   */
  public static RelationSchema parse(String source, String origin, Grammar grammar) {
    return new Parser(DslLine.read(source, origin), origin, grammar).schema();
  }

  /** Loads the schema at the absolute class-path location {@code resource}. */
  public static RelationSchema load(String resource, Grammar grammar) {
    return parse(Resources.read(resource), resource, grammar);
  }

  private static final class ObjectDeclaration {
    final DslLine line;
    final String name;
    Optional<DslLine> keyLine = Optional.empty();
    final List<DslLine> attributeLines = new ArrayList<>();
    List<String> key = List.of();
    List<Production> sources = List.of();
    List<ValueSort> keySorts = List.of();
    Grammar.Context scope;

    ObjectDeclaration(DslLine line, String name, Grammar.Context scope) {
      this.line = line;
      this.name = name;
      this.scope = scope;
    }
  }

  private static final class RelationDeclaration {
    final DslLine line;
    final String name;
    final boolean symmetric;
    final List<DslLine> roleLines = new ArrayList<>();
    final List<DslLine> joinLines = new ArrayList<>();
    final List<DslLine> requireLines = new ArrayList<>();

    RelationDeclaration(DslLine line, String name, boolean symmetric) {
      this.line = line;
      this.name = name;
      this.symmetric = symmetric;
    }
  }

  private static final class Parser {
    private final List<DslLine> lines;
    private final String origin;
    private final Grammar grammar;
    private final Map<String, ObjectDeclaration> objectDeclarations = new LinkedHashMap<>();
    private final Map<String, RelationDeclaration> relationDeclarations = new LinkedHashMap<>();
    private final List<DslLine> domainLines = new ArrayList<>();
    private final Map<String, ObjectType> objects = new LinkedHashMap<>();
    private final Map<String, RelationType> relations = new LinkedHashMap<>();

    Parser(List<DslLine> lines, String origin, Grammar grammar) {
      this.lines = lines;
      this.origin = origin;
      this.grammar = grammar;
    }

    RelationSchema schema() {
      if (lines.isEmpty() || !lines.getFirst().keyword().equals("relations")) {
        throw new DslException(
            origin, lines.isEmpty() ? 1 : lines.getFirst().number(), "expected 'relations <name>'");
      }
      DslLine.Cursor header = lines.getFirst().cursor();
      String name = identifier(header, "schema name");
      header.end();
      group();
      resolveKeys();
      objectDeclarations.values().forEach(this::resolveObject);
      relationDeclarations.values().forEach(this::resolveRelation);
      List<DomainRule> domains = new ArrayList<>();
      domainLines.forEach(line -> domains.add(domain(line)));
      return new RelationSchema(name, grammar, objects.values(), relations.values(), domains);
    }

    private void group() {
      Optional<ObjectDeclaration> object = Optional.empty();
      Optional<RelationDeclaration> relation = Optional.empty();
      for (DslLine line : lines.subList(1, lines.size())) {
        switch (line.keyword()) {
          case "object" -> {
            DslLine.Cursor cursor = line.cursor();
            String name = identifier(cursor, "object name");
            cursor.end();
            if (objectDeclarations.containsKey(name)) {
              throw line.error("object '" + name + "' is already declared");
            }
            ObjectDeclaration declaration = new ObjectDeclaration(line, name, grammar.root());
            objectDeclarations.put(name, declaration);
            object = Optional.of(declaration);
            relation = Optional.empty();
          }
          case "relation" -> {
            DslLine.Cursor cursor = line.cursor();
            String name = identifier(cursor, "relation name");
            boolean symmetric = cursor.accept("symmetric");
            cursor.end();
            if (relationDeclarations.containsKey(name)) {
              throw line.error("relation '" + name + "' is already declared");
            }
            RelationDeclaration declaration = new RelationDeclaration(line, name, symmetric);
            relationDeclarations.put(name, declaration);
            relation = Optional.of(declaration);
            object = Optional.empty();
          }
          case "key" -> {
            ObjectDeclaration declaration =
                object.orElseThrow(() -> line.error("'key' outside an object"));
            if (declaration.keyLine.isPresent()) {
              throw line.error("object '" + declaration.name + "' already has a key");
            }
            declaration.keyLine = Optional.of(line);
          }
          case "attribute" ->
              object
                  .orElseThrow(() -> line.error("'attribute' outside an object"))
                  .attributeLines
                  .add(line);
          case "role" ->
              relation
                  .orElseThrow(() -> line.error("'role' outside a relation"))
                  .roleLines
                  .add(line);
          case "join" ->
              relation
                  .orElseThrow(() -> line.error("'join' outside a relation"))
                  .joinLines
                  .add(line);
          case "require" ->
              relation
                  .orElseThrow(() -> line.error("'require' outside a relation"))
                  .requireLines
                  .add(line);
          case "domain" -> {
            domainLines.add(line);
            object = Optional.empty();
            relation = Optional.empty();
          }
          default -> throw line.error("unknown declaration '" + line.keyword() + "'");
        }
      }
    }

    private void resolveKeys() {
      ObjectDeclaration router = objectDeclarations.get(ObjectType.ROUTER);
      if (router == null) {
        throw new DslException(
            origin, lines.getFirst().number(), "the schema must declare object 'router'");
      }
      for (ObjectDeclaration declaration : objectDeclarations.values()) {
        if (declaration == router) {
          router.keyLine.ifPresent(
              line -> {
                throw line.error("the router object has no key");
              });
          continue;
        }
        DslLine line =
            declaration.keyLine.orElseThrow(
                () -> declaration.line.error("object '" + declaration.name + "' needs a key"));
        DslLine.Cursor cursor = line.cursor();
        List<String> key = new ArrayList<>();
        while (!cursor.accept("from")) {
          key.add(identifier(cursor, "key slot or 'from'"));
        }
        if (key.isEmpty() || new HashSet<>(key).size() != key.size()) {
          throw line.error("a key lists one or more distinct slots");
        }
        List<Production> sources = new ArrayList<>();
        for (String id : cursor.rest()) {
          sources.add(production(line, id));
        }
        if (sources.isEmpty()) {
          throw line.error("a key names one or more source productions");
        }
        List<ValueSort> keySorts = new ArrayList<>();
        for (String slotName : key) {
          ValueSort sort = null;
          for (Production source : sources) {
            Symbol.Slot slot = slot(line, source, slotName);
            if (slot.repeated()) {
              throw line.error(
                  "key slot '" + slotName + "' of " + source.id() + " takes several values");
            }
            if (sort != null && !sort.equals(slot.sort())) {
              throw line.error("key slot '" + slotName + "' has different sorts in the sources");
            }
            sort = slot.sort();
          }
          keySorts.add(sort);
        }
        declaration.key = List.copyOf(key);
        declaration.sources = List.copyOf(sources);
        declaration.keySorts = List.copyOf(keySorts);
        declaration.scope = commonAncestor(sources.stream().map(Production::context).toList());
      }
    }

    private void resolveObject(ObjectDeclaration declaration) {
      SequencedMap<String, Attribute> attributes = new LinkedHashMap<>();
      for (int index = 0; index < declaration.key.size(); index++) {
        String component = declaration.key.get(index);
        attributes.put(
            component,
            new Attribute(
                component,
                new Attribute.Type.Value(declaration.keySorts.get(index)),
                false,
                new Attribute.Source.KeyComponent()));
      }
      if (!declaration.name.equals(ObjectType.ROUTER)) {
        attributes.put(
            ROUTER_ATTRIBUTE,
            new Attribute(
                ROUTER_ATTRIBUTE,
                new Attribute.Type.Reference(ObjectType.ROUTER),
                false,
                new Attribute.Source.Owner()));
      }
      for (DslLine line : declaration.attributeLines) {
        Attribute attribute = attribute(declaration, line);
        if (attributes.putIfAbsent(attribute.name(), attribute) != null) {
          throw line.error(
              "object '" + declaration.name + "' already has attribute '" + attribute.name() + "'");
        }
      }
      objects.put(
          declaration.name,
          new ObjectType(declaration.name, declaration.key, declaration.sources, attributes));
    }

    private Attribute attribute(ObjectDeclaration declaration, DslLine line) {
      DslLine.Cursor cursor = line.cursor();
      String name = identifier(cursor, "attribute name");
      boolean many = cursor.accept("many");
      String form = cursor.next("'=', 'ref', 'flag' or 'derived'");
      return switch (form) {
        case "=", "ref" -> {
          Optional<String> target = Optional.empty();
          if (form.equals("ref")) {
            target = Optional.of(knownObject(line, identifier(cursor, "referenced object")));
            cursor.expect("=");
          }
          List<Attribute.SlotRef> slots = slotRefs(line, cursor);
          List<String> by = by(cursor);
          List<Attribute.Condition> where =
              where(line, cursor, slots.stream().map(Attribute.SlotRef::production).toList());
          ValueSort sort = null;
          for (Attribute.SlotRef ref : slots) {
            Symbol.Slot slot = slot(line, ref.production(), ref.slot());
            if (slot.repeated() && !many) {
              throw line.error(
                  "slot " + ref + " takes several values; declare the attribute 'many'");
            }
            if (sort != null && !sort.equals(slot.sort())) {
              throw line.error("the slots of attribute '" + name + "' have different sorts");
            }
            sort = slot.sort();
            checkBinding(declaration, ref.production(), by, line);
          }
          Attribute.Type type = new Attribute.Type.Value(sort);
          if (target.isPresent()) {
            checkReference(line, target.get(), sort);
            type = new Attribute.Type.Reference(target.get());
          }
          yield new Attribute(name, type, many, new Attribute.Source.Slots(slots, by, where));
        }
        case "flag" -> {
          if (many) {
            throw line.error("a flag has one value");
          }
          List<Production> productions = new ArrayList<>();
          while (cursor.hasNext() && !isClause(cursor.peek().orElseThrow())) {
            productions.add(production(line, cursor.next("production")));
          }
          if (productions.isEmpty()) {
            throw line.error("a flag names one or more productions");
          }
          List<String> by = by(cursor);
          List<Attribute.Condition> where = where(line, cursor, productions);
          productions.forEach(production -> checkBinding(declaration, production, by, line));
          yield new Attribute(
              name,
              new Attribute.Type.Flag(),
              false,
              new Attribute.Source.Presence(productions, by, where));
        }
        case "derived" -> {
          String function = identifier(cursor, "function name");
          String kind = cursor.next("sort, 'ref' or 'flag'");
          Attribute.Type type;
          if (kind.equals("flag")) {
            type = new Attribute.Type.Flag();
          } else if (kind.equals("ref")) {
            type =
                new Attribute.Type.Reference(
                    knownObject(line, identifier(cursor, "referenced object")));
          } else {
            type =
                new Attribute.Type.Value(
                    grammar
                        .sort(kind)
                        .orElseThrow(() -> line.error("unknown sort '" + kind + "'")));
          }
          cursor.end();
          yield new Attribute(name, type, many, new Attribute.Source.Derived(function));
        }
        default ->
            throw line.error("expected '=', 'ref', 'flag' or 'derived' but found '" + form + "'");
      };
    }

    private List<Attribute.SlotRef> slotRefs(DslLine line, DslLine.Cursor cursor) {
      List<Attribute.SlotRef> refs = new ArrayList<>();
      while (cursor.hasNext() && !isClause(cursor.peek().orElseThrow())) {
        String token = cursor.next("production.slot");
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
          throw line.error("expected production.slot but found '" + token + "'");
        }
        Production production = production(line, token.substring(0, dot));
        String slotName = token.substring(dot + 1);
        slot(line, production, slotName);
        refs.add(new Attribute.SlotRef(production, slotName));
      }
      if (refs.isEmpty()) {
        throw line.error("an attribute reads one or more slots");
      }
      return refs;
    }

    private static boolean isClause(String token) {
      return token.equals("by") || token.equals("where");
    }

    private List<String> by(DslLine.Cursor cursor) {
      List<String> slots = new ArrayList<>();
      if (cursor.accept("by")) {
        while (cursor.hasNext() && !cursor.peek().orElseThrow().equals("where")) {
          slots.add(identifier(cursor, "slot"));
        }
        if (slots.isEmpty()) {
          throw cursor.error("'by' lists one or more slots");
        }
      }
      return slots;
    }

    private List<Attribute.Condition> where(
        DslLine line, DslLine.Cursor cursor, List<Production> productions) {
      List<Attribute.Condition> conditions = new ArrayList<>();
      while (cursor.accept("where")) {
        String slotName = identifier(cursor, "slot");
        cursor.expect("=");
        String literal = cursor.next("literal");
        for (Production production : productions) {
          Symbol.Slot slot = slot(line, production, slotName);
          if (!slot.accepts(literal)) {
            throw line.error(
                "'" + literal + "' is not a " + slot.sort().name() + " for slot " + slotName);
          }
        }
        conditions.add(new Attribute.Condition(slotName, literal));
      }
      cursor.end();
      return conditions;
    }

    private void checkBinding(
        ObjectDeclaration object, Production production, List<String> by, DslLine line) {
      if (object.name.equals(ObjectType.ROUTER)) {
        if (!by.isEmpty()) {
          throw line.error("statements bind to the router without 'by'");
        }
        return;
      }
      boolean own = object.sources.contains(production);
      boolean inside =
          object.sources.stream()
              .anyMatch(source -> source.opens().equals(Optional.of(production.context())));
      if (own || inside) {
        if (!by.isEmpty()) {
          throw line.error(
              "statements of " + production.id() + " bind to '" + object.name + "' without 'by'");
        }
        return;
      }
      List<String> slots = by.isEmpty() ? object.key : by;
      if (slots.size() != object.key.size()) {
        throw line.error("'by' lists one slot per key slot of '" + object.name + "'");
      }
      for (int index = 0; index < slots.size(); index++) {
        Symbol.Slot slot = slot(line, production, slots.get(index));
        if (!slot.sort().overlaps(object.keySorts.get(index))) {
          throw line.error(
              "slot "
                  + slot.name()
                  + " of "
                  + production.id()
                  + " cannot name a '"
                  + object.name
                  + "'");
        }
      }
      if (!within(production.context(), object.scope)) {
        throw line.error(production.id() + " is outside the context of '" + object.name + "'");
      }
    }

    private void checkReference(DslLine line, String target, ValueSort sort) {
      ObjectDeclaration declaration = objectDeclarations.get(target);
      if (declaration.keySorts.size() != 1) {
        throw line.error(
            "object '" + target + "' does not have a one-slot key and cannot be referenced");
      }
      if (!declaration.keySorts.getFirst().overlaps(sort)) {
        throw line.error("a " + sort.name() + " cannot name a '" + target + "'");
      }
    }

    private void resolveRelation(RelationDeclaration declaration) {
      List<RelationType.Role> roles = new ArrayList<>();
      for (DslLine line : declaration.roleLines) {
        DslLine.Cursor cursor = line.cursor();
        String name = identifier(cursor, "role name");
        String object = knownObject(line, identifier(cursor, "object type"));
        String cardinality = cursor.next("'each' or 'optional'");
        cursor.end();
        RelationType.Cardinality parsed =
            switch (cardinality) {
              case "each" -> RelationType.Cardinality.EACH;
              case "optional" -> RelationType.Cardinality.OPTIONAL;
              default ->
                  throw line.error("expected 'each' or 'optional' but found '" + cardinality + "'");
            };
        if ((parsed == RelationType.Cardinality.EACH) != roles.isEmpty()) {
          throw line.error("the first role and only the first role is 'each'");
        }
        if (roles.stream().anyMatch(role -> role.name().equals(name))) {
          throw line.error("role '" + name + "' is already declared");
        }
        roles.add(new RelationType.Role(name, object, parsed));
      }
      if (roles.isEmpty()) {
        throw declaration.line.error("relation '" + declaration.name + "' has no roles");
      }
      if (declaration.symmetric
          && (roles.size() != 2 || !roles.get(0).object().equals(roles.get(1).object()))) {
        throw declaration.line.error("a symmetric relation has two roles of the same object type");
      }
      RelationType draft =
          new RelationType(declaration.name, declaration.symmetric, roles, List.of(), List.of());
      List<RelationType.Join> joins = new ArrayList<>();
      Set<String> joined = new HashSet<>();
      for (DslLine line : declaration.joinLines) {
        DslLine.Cursor cursor = line.cursor();
        AttributePath left = path(line, cursor.next("path"));
        cursor.expect("=");
        AttributePath right = path(line, cursor.next("path"));
        cursor.end();
        compatible(line, typeOf(line, draft, left), typeOf(line, draft, right));
        joined.add(left.role());
        joined.add(right.role());
        joins.add(new RelationType.Join(left, right));
      }
      for (RelationType.Role role : roles.subList(1, roles.size())) {
        if (!joined.contains(role.name())) {
          throw declaration.line.error("no join determines role '" + role.name() + "'");
        }
      }
      List<Requirement> requirements = new ArrayList<>();
      for (DslLine line : declaration.requireLines) {
        requirements.add(requirement(line, draft));
      }
      relations.put(
          declaration.name,
          new RelationType(declaration.name, declaration.symmetric, roles, joins, requirements));
    }

    private Requirement requirement(DslLine line, RelationType relation) {
      DslLine.Cursor cursor = line.cursor();
      String strengthToken = cursor.next("'hard', 'soft' or 'convention'");
      Requirement.Strength strength =
          switch (strengthToken) {
            case "hard" -> Requirement.Strength.HARD;
            case "soft" -> Requirement.Strength.SOFT;
            case "convention" -> Requirement.Strength.CONVENTION;
            default ->
                throw line.error(
                    "expected 'hard', 'soft' or 'convention' but found '" + strengthToken + "'");
          };
      String kind = cursor.next("constraint");
      Constraint constraint =
          switch (kind) {
            case "exists" -> new Constraint.Exists(checkedPath(line, relation, cursor));
            case "absent" -> new Constraint.Absent(checkedPath(line, relation, cursor));
            case "distinct" -> new Constraint.Distinct(checkedPath(line, relation, cursor));
            case "resolves" -> {
              AttributePath path = path(line, cursor.next("path"));
              if (path.attributes().isEmpty()
                  || !(typeOf(line, relation, path) instanceof Attribute.Type.Reference)) {
                throw line.error("'resolves' needs a path that ends at a reference");
              }
              yield new Constraint.Resolves(path);
            }
            case "equal", "agree" -> {
              AttributePath left = checkedPath(line, relation, cursor);
              AttributePath right = checkedPath(line, relation, cursor);
              compatible(line, typeOf(line, relation, left), typeOf(line, relation, right));
              yield kind.equals("equal")
                  ? new Constraint.Equal(left, right)
                  : new Constraint.Agree(left, right);
            }
            default -> throw line.error("unknown constraint '" + kind + "'");
          };
      Optional<AttributePath> guard = Optional.empty();
      if (cursor.accept("when")) {
        guard = Optional.of(checkedPath(line, relation, cursor));
      }
      cursor.end();
      return new Requirement(strength, constraint, guard);
    }

    private AttributePath checkedPath(DslLine line, RelationType relation, DslLine.Cursor cursor) {
      AttributePath path = path(line, cursor.next("path"));
      typeOf(line, relation, path);
      return path;
    }

    private Attribute.Type typeOf(DslLine line, RelationType relation, AttributePath path) {
      RelationType.Role role =
          relation
              .role(path.role())
              .orElseThrow(() -> line.error("unknown role '" + path.role() + "'"));
      Attribute.Type type = new Attribute.Type.Reference(role.object());
      for (String attributeName : path.attributes()) {
        if (!(type instanceof Attribute.Type.Reference reference)) {
          throw line.error("'" + path + "' follows an attribute that is not a reference");
        }
        ObjectType object = objects.get(reference.object());
        type =
            object
                .attribute(attributeName)
                .orElseThrow(
                    () ->
                        line.error(
                            "'"
                                + reference.object()
                                + "' has no attribute '"
                                + attributeName
                                + "'"))
                .type();
      }
      return type;
    }

    private static void compatible(DslLine line, Attribute.Type left, Attribute.Type right) {
      boolean ok =
          switch (left) {
            case Attribute.Type.Value value ->
                right instanceof Attribute.Type.Value other && value.sort().overlaps(other.sort());
            case Attribute.Type.Flag _ -> right instanceof Attribute.Type.Flag;
            case Attribute.Type.Reference reference ->
                right instanceof Attribute.Type.Reference other
                    && reference.object().equals(other.object());
          };
      if (!ok) {
        throw line.error("the two sides have incompatible types");
      }
    }

    private DomainRule domain(DslLine line) {
      DslLine.Cursor cursor = line.cursor();
      Production production = production(line, cursor.next("production"));
      List<String> slotNames = List.of(COMMA.split(cursor.next("slots"), -1));
      if (new HashSet<>(slotNames).size() != slotNames.size() || slotNames.size() > 2) {
        throw line.error("a domain fills one slot, or an address and a mask from one prefix");
      }
      List<ValueSort> sorts = new ArrayList<>();
      for (String slotName : slotNames) {
        sorts.add(slot(line, production, slotName).sort());
      }
      String kind = cursor.next("domain origin");
      DomainRule.Origin domainOrigin =
          switch (kind) {
            case "values" -> {
              String reference = cursor.next("object.attribute");
              int dot = reference.indexOf('.');
              if (dot <= 0) {
                throw line.error("expected object.attribute but found '" + reference + "'");
              }
              String object = knownObject(line, reference.substring(0, dot));
              String attribute = reference.substring(dot + 1);
              Attribute.Type type =
                  objects
                      .get(object)
                      .attribute(attribute)
                      .orElseThrow(
                          () -> line.error("'" + object + "' has no attribute '" + attribute + "'"))
                      .type();
              checkValues(line, type, sorts);
              cursor.expect("in");
              yield new DomainRule.Origin.Values(object, attribute, scope(line, cursor));
            }
            case "names" -> {
              String object = knownObject(line, identifier(cursor, "object type"));
              if (sorts.size() != 1) {
                throw line.error("names fill one slot");
              }
              checkReference(line, object, sorts.getFirst());
              cursor.expect("in");
              yield new DomainRule.Origin.Names(object, scope(line, cursor));
            }
            case "related" -> {
              String relationName = identifier(cursor, "relation");
              RelationType relation =
                  Optional.ofNullable(relations.get(relationName))
                      .orElseThrow(() -> line.error("unknown relation '" + relationName + "'"));
              AttributePath path = path(line, cursor.next("path"));
              checkValues(line, typeOf(line, relation, path), sorts);
              yield new DomainRule.Origin.Related(relationName, path);
            }
            case "verifier" -> {
              String observation = cursor.next("observation");
              DomainRule.Observation parsed =
                  switch (observation) {
                    case "destination" -> DomainRule.Observation.DESTINATION;
                    case "source" -> DomainRule.Observation.SOURCE;
                    case "waypoint" -> DomainRule.Observation.WAYPOINT;
                    case "route-holder" -> DomainRule.Observation.ROUTE_HOLDER;
                    default -> throw line.error("unknown observation '" + observation + "'");
                  };
              if (parsed == DomainRule.Observation.DESTINATION) {
                checkPrefixSlots(line, sorts);
              } else if (sorts.size() != 1
                  || !(sorts.getFirst().lexeme() instanceof Lexeme.Name
                      || sorts.getFirst().lexeme() instanceof Lexeme.Address)) {
                throw line.error("routers fill one name slot or, by loopback, one address slot");
              }
              yield new DomainRule.Origin.Verifier(parsed);
            }
            case "fresh" -> {
              if (sorts.size() != 1
                  || sorts.getFirst().lexeme() instanceof Lexeme.Enumeration
                  || sorts.getFirst().lexeme() instanceof Lexeme.Text) {
                throw line.error("fresh values fill one slot that is not an enumeration or text");
              }
              yield new DomainRule.Origin.Fresh();
            }
            case "literals" -> {
              if (sorts.size() != 1 || !(sorts.getFirst().lexeme() instanceof Lexeme.Enumeration)) {
                throw line.error("literals fill one enumeration slot");
              }
              yield new DomainRule.Origin.Literals();
            }
            case "observed" -> {
              cursor.expect("in");
              yield new DomainRule.Origin.Observed(scope(line, cursor));
            }
            default -> throw line.error("unknown domain origin '" + kind + "'");
          };
      cursor.end();
      return new DomainRule(production, slotNames, domainOrigin);
    }

    private static void checkValues(DslLine line, Attribute.Type type, List<ValueSort> sorts) {
      if (!(type instanceof Attribute.Type.Value value)) {
        throw line.error("domain values come from a value attribute");
      }
      if (sorts.size() == 2) {
        if (!(value.sort().lexeme() instanceof Lexeme.AddressPrefix)) {
          throw line.error("two slots are filled from prefixes");
        }
        checkPrefixSlots(line, sorts);
      } else if (!value.sort().overlaps(sorts.getFirst())) {
        throw line.error(
            "a " + value.sort().name() + " cannot fill a " + sorts.getFirst().name() + " slot");
      }
    }

    private static void checkPrefixSlots(DslLine line, List<ValueSort> sorts) {
      boolean prefix =
          sorts.size() == 1 && sorts.getFirst().lexeme() instanceof Lexeme.AddressPrefix;
      boolean pair =
          sorts.size() == 2
              && sorts.get(0).lexeme() instanceof Lexeme.Address
              && (sorts.get(1).lexeme() instanceof Lexeme.Mask
                  || sorts.get(1).lexeme() instanceof Lexeme.Wildcard);
      if (!prefix && !pair) {
        throw line.error(
            "a prefix fills a prefix slot, or an address slot and a mask or wildcard slot");
      }
    }

    private static DomainRule.Scope scope(DslLine line, DslLine.Cursor cursor) {
      String scope = cursor.next("'router', 'neighbours' or 'network'");
      return switch (scope) {
        case "router" -> DomainRule.Scope.ROUTER;
        case "neighbours" -> DomainRule.Scope.NEIGHBOURS;
        case "network" -> DomainRule.Scope.NETWORK;
        default -> throw line.error("unknown scope '" + scope + "'");
      };
    }

    private Grammar.Context commonAncestor(List<Grammar.Context> contexts) {
      Grammar.Context candidate = contexts.getFirst();
      while (!allWithin(contexts, candidate)) {
        candidate = grammar.context(candidate.parent().orElseThrow()).orElseThrow();
      }
      return candidate;
    }

    private boolean allWithin(List<Grammar.Context> contexts, Grammar.Context ancestor) {
      return contexts.stream().allMatch(context -> within(context, ancestor));
    }

    private boolean within(Grammar.Context context, Grammar.Context ancestor) {
      Optional<Grammar.Context> current = Optional.of(context);
      while (current.isPresent()) {
        if (current.get().equals(ancestor)) {
          return true;
        }
        current = current.get().parent().flatMap(grammar::context);
      }
      return false;
    }

    private Production production(DslLine line, String id) {
      return grammar
          .production(id)
          .orElseThrow(() -> line.error("unknown production '" + id + "'"));
    }

    private static Symbol.Slot slot(DslLine line, Production production, String name) {
      return production
          .slot(name)
          .orElseThrow(
              () -> line.error("production " + production.id() + " has no slot '" + name + "'"));
    }

    private String knownObject(DslLine line, String name) {
      if (!objectDeclarations.containsKey(name)) {
        throw line.error("unknown object '" + name + "'");
      }
      return name;
    }

    private static AttributePath path(DslLine line, String text) {
      try {
        return AttributePath.parse(text);
      } catch (IllegalArgumentException invalid) {
        throw line.error(invalid.getMessage());
      }
    }

    private static String identifier(DslLine.Cursor cursor, String what) {
      String token = cursor.next(what);
      if (!IDENTIFIER.matcher(token).matches()) {
        throw cursor.error(
            "invalid " + what + " '" + token + "'; use lower-case words joined by '-'");
      }
      return token;
    }
  }
}
