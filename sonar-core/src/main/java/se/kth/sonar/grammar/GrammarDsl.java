package se.kth.sonar.grammar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import se.kth.sonar.common.DslException;
import se.kth.sonar.common.DslLine;
import se.kth.sonar.common.Resources;

/**
 * Reads grammars written in the grammar language.
 *
 * <p>A grammar source is line based; blank lines and lines starting with {@code #} are ignored.
 *
 * <pre>
 * grammar cisco-ios
 * comment !
 * sort asn integer 1..4294967295
 * sort interface interface Loopback GigabitEthernet
 * context root
 * context router-bgp in root
 * production router-bgp root opens router-bgp : router bgp &lt;asn:asn&gt;
 * production bgp-router-id router-bgp : bgp router-id &lt;id:address&gt;
 * </pre>
 *
 * <p>Lexemes are {@code ipv4}, {@code mask}, {@code wildcard}, {@code prefix}, {@code iso-net},
 * {@code integer min..max}, {@code interface stem...}, {@code name}, {@code enum literal...},
 * {@code community} and {@code text}. A production lists keywords and slots written {@code
 * <slot:sort>}; a trailing {@code +} lets one slot take one or more values. The options {@code
 * opens context} and {@code closes} give the statement's effect on the context.
 */
public final class GrammarDsl {
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
  private static final Pattern SLOT = Pattern.compile("<([^:<>]+):([^:<>]+)>(\\+?)");
  private static final Pattern RANGE = Pattern.compile("(\\d{1,18})\\.\\.(\\d{1,18})");

  private GrammarDsl() {}

  /**
   * Parses and validates the grammar in {@code source}.
   *
   * @param source the grammar text
   * @param origin where the text came from, used in error messages
   * @throws DslException if the source is malformed or the grammar is invalid
   */
  public static Grammar parse(String source, String origin) {
    return new Parser(DslLine.read(source, origin), origin).grammar();
  }

  /** Loads and validates the grammar at the absolute class-path location {@code resource}. */
  public static Grammar load(String resource) {
    return parse(Resources.read(resource), resource);
  }

  private static final class Parser {
    private final List<DslLine> lines;
    private final String origin;
    private Optional<String> commentMarker = Optional.empty();
    private final Map<String, ValueSort> sorts = new LinkedHashMap<>();
    private final Map<String, Grammar.Context> contexts = new LinkedHashMap<>();
    private final Map<String, DslLine> contextLines = new HashMap<>();
    private final List<DslLine> productionLines = new ArrayList<>();
    private final Map<String, Production> productions = new LinkedHashMap<>();
    private final Map<String, DslLine> productionDeclarations = new HashMap<>();

    Parser(List<DslLine> lines, String origin) {
      this.lines = lines;
      this.origin = origin;
    }

    Grammar grammar() {
      if (lines.isEmpty()) {
        throw new DslException(origin, 1, "expected 'grammar <name>'");
      }
      DslLine header = lines.getFirst();
      if (!header.keyword().equals("grammar")) {
        throw header.error("expected 'grammar <name>' before any declaration");
      }
      DslLine.Cursor cursor = header.cursor();
      String name = identifier(cursor, "grammar name");
      cursor.end();
      for (DslLine line : lines.subList(1, lines.size())) {
        switch (line.keyword()) {
          case "comment" -> comment(line);
          case "sort" -> sort(line);
          case "context" -> context(line);
          case "production" -> productionLines.add(line);
          default -> throw line.error("unknown declaration '" + line.keyword() + "'");
        }
      }
      checkContextTree();
      productionLines.forEach(this::production);
      checkOpened();
      checkAmbiguity();
      return new Grammar(
          name, commentMarker, sorts.values(), contexts.values(), productions.values());
    }

    private void comment(DslLine line) {
      if (commentMarker.isPresent()) {
        throw line.error("the comment marker is already declared");
      }
      DslLine.Cursor cursor = line.cursor();
      commentMarker = Optional.of(cursor.next("comment marker"));
      cursor.end();
    }

    private void sort(DslLine line) {
      DslLine.Cursor cursor = line.cursor();
      String name = identifier(cursor, "sort name");
      if (sorts.containsKey(name)) {
        throw line.error("sort '" + name + "' is already declared");
      }
      sorts.put(name, new ValueSort(name, lexeme(cursor)));
    }

    private Lexeme lexeme(DslLine.Cursor cursor) {
      String kind = cursor.next("lexeme");
      Lexeme lexeme;
      try {
        lexeme =
            switch (kind) {
              case "ipv4" -> new Lexeme.Address();
              case "mask" -> new Lexeme.Mask();
              case "wildcard" -> new Lexeme.Wildcard();
              case "prefix" -> new Lexeme.AddressPrefix();
              case "iso-net" -> new Lexeme.IsoNet();
              case "name" -> new Lexeme.Name();
              case "community" -> new Lexeme.Community();
              case "text" -> new Lexeme.Text();
              case "integer" -> range(cursor);
              case "interface" -> new Lexeme.InterfaceName(cursor.rest());
              case "enum" -> new Lexeme.Enumeration(cursor.rest());
              default -> throw cursor.error("unknown lexeme '" + kind + "'");
            };
      } catch (IllegalArgumentException invalid) {
        throw cursor.error(invalid.getMessage());
      }
      cursor.end();
      return lexeme;
    }

    private static Lexeme range(DslLine.Cursor cursor) {
      String text = cursor.next("integer range min..max");
      Matcher matcher = RANGE.matcher(text);
      if (!matcher.matches()) {
        throw cursor.error("expected an integer range min..max but found '" + text + "'");
      }
      return new Lexeme.IntegerRange(
          Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)));
    }

    private void context(DslLine line) {
      DslLine.Cursor cursor = line.cursor();
      String name = identifier(cursor, "context name");
      if (contexts.containsKey(name)) {
        throw line.error("context '" + name + "' is already declared");
      }
      Optional<String> parent = Optional.empty();
      if (cursor.accept("in")) {
        parent = Optional.of(identifier(cursor, "parent context"));
      }
      cursor.end();
      contexts.put(name, new Grammar.Context(name, parent));
      contextLines.put(name, line);
    }

    private void checkContextTree() {
      List<Grammar.Context> roots =
          contexts.values().stream().filter(Grammar.Context::isRoot).toList();
      if (roots.size() != 1) {
        throw new DslException(
            origin,
            roots.size() > 1 ? contextLines.get(roots.get(1).name()).number() : 1,
            "a grammar has exactly one root context, found " + roots.size());
      }
      for (Grammar.Context context : contexts.values()) {
        Set<String> seen = new HashSet<>();
        Optional<String> ancestor = context.parent();
        while (ancestor.isPresent()) {
          Grammar.Context next = contexts.get(ancestor.get());
          if (next == null) {
            throw contextLines
                .get(context.name())
                .error("unknown parent context '" + ancestor.get() + "'");
          }
          if (!seen.add(next.name())) {
            throw contextLines
                .get(context.name())
                .error("context '" + context.name() + "' is inside itself");
          }
          ancestor = next.parent();
        }
      }
    }

    private void production(DslLine line) {
      DslLine.Cursor cursor = line.cursor();
      String id = identifier(cursor, "production name");
      if (productions.containsKey(id)) {
        throw line.error("production '" + id + "' is already declared");
      }
      Grammar.Context context = knownContext(cursor, cursor.next("context"));
      Optional<Grammar.Context> opens = Optional.empty();
      boolean closes = false;
      while (!cursor.accept(":")) {
        String option = cursor.next("':' before the symbols");
        switch (option) {
          case "opens" -> {
            if (opens.isPresent()) {
              throw line.error("a production opens at most one context");
            }
            opens = Optional.of(knownContext(cursor, cursor.next("context to open")));
          }
          case "closes" -> {
            if (closes) {
              throw line.error("'closes' is given twice");
            }
            closes = true;
          }
          default -> throw line.error("unknown production option '" + option + "'");
        }
      }
      List<String> tokens = cursor.rest();
      if (tokens.isEmpty()) {
        throw line.error("production '" + id + "' has no symbols");
      }
      List<Symbol> symbols = new ArrayList<>(tokens.size());
      for (String token : tokens) {
        symbols.add(symbol(line, token));
      }
      try {
        productions.put(id, new Production(id, context, symbols, opens, closes));
      } catch (IllegalArgumentException invalid) {
        throw line.error(invalid.getMessage());
      }
      productionDeclarations.put(id, line);
    }

    private Symbol symbol(DslLine line, String token) {
      Matcher slot = SLOT.matcher(token);
      if (slot.matches()) {
        String name = slot.group(1);
        if (!IDENTIFIER.matcher(name).matches()) {
          throw line.error("invalid slot name '" + name + "'");
        }
        ValueSort sort = sorts.get(slot.group(2));
        if (sort == null) {
          throw line.error("unknown sort '" + slot.group(2) + "'");
        }
        return new Symbol.Slot(name, sort, !slot.group(3).isEmpty());
      }
      if (token.startsWith("<")) {
        throw line.error("malformed slot '" + token + "'; write <name:sort>");
      }
      return new Symbol.Keyword(token);
    }

    private Grammar.Context knownContext(DslLine.Cursor cursor, String name) {
      Grammar.Context context = contexts.get(name);
      if (context == null) {
        throw cursor.error("unknown context '" + name + "'");
      }
      return context;
    }

    private void checkOpened() {
      Set<String> opened = new HashSet<>();
      productions
          .values()
          .forEach(production -> production.opens().ifPresent(c -> opened.add(c.name())));
      for (Grammar.Context context : contexts.values()) {
        if (!context.isRoot() && !opened.contains(context.name())) {
          throw contextLines
              .get(context.name())
              .error("no production opens context '" + context.name() + "'");
        }
      }
    }

    private void checkAmbiguity() {
      List<Production> all = List.copyOf(productions.values());
      for (int later = 0; later < all.size(); later++) {
        for (int earlier = 0; earlier < later; earlier++) {
          Production a = all.get(earlier);
          Production b = all.get(later);
          if (a.context().equals(b.context()) && a.overlaps(b)) {
            throw productionDeclarations
                .get(b.id())
                .error(
                    "productions '"
                        + a.id()
                        + "' (line "
                        + productionDeclarations.get(a.id()).number()
                        + ") and '"
                        + b.id()
                        + "' can match the same line in context '"
                        + a.context().name()
                        + "'");
          }
        }
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
