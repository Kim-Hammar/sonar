package se.kth.sonar.grammar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import se.kth.sonar.common.NotImplemented;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;

/**
 * The syntax of a configuration dialect: value sorts, a tree of contexts, and the productions
 * allowed in each context. Grammars are created by {@link GrammarDsl}, which validates them; in
 * particular, no two productions of a context match the same line.
 */
public final class Grammar {
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final String name;
  private final Optional<String> commentMarker;
  private final Map<String, ValueSort> sorts;
  private final Map<String, Context> contexts;
  private final Map<String, Production> productions;
  private final Map<String, List<Production>> byContext;
  private final Map<String, Map<String, List<Production>>> byKeyword;
  private final Map<String, List<Production>> slotFirst;

  Grammar(
      String name,
      Optional<String> commentMarker,
      Collection<ValueSort> sorts,
      Collection<Context> contexts,
      Collection<Production> productions) {
    this.name = name;
    this.commentMarker = commentMarker;
    this.sorts = index(sorts, ValueSort::name);
    this.contexts = index(contexts, Context::name);
    this.productions = index(productions, Production::id);
    Map<String, List<Production>> inContext = new LinkedHashMap<>();
    Map<String, Map<String, List<Production>>> keyword = new LinkedHashMap<>();
    Map<String, List<Production>> slot = new LinkedHashMap<>();
    for (Context context : contexts) {
      inContext.put(context.name(), new ArrayList<>());
      keyword.put(context.name(), new LinkedHashMap<>());
      slot.put(context.name(), new ArrayList<>());
    }
    for (Production production : productions) {
      String context = production.context().name();
      inContext.get(context).add(production);
      if (production.symbols().getFirst() instanceof Symbol.Keyword first) {
        keyword
            .get(context)
            .computeIfAbsent(first.text(), text -> new ArrayList<>())
            .add(production);
      } else {
        slot.get(context).add(production);
      }
    }
    this.byContext = freezeLists(inContext);
    Map<String, Map<String, List<Production>>> frozen = new LinkedHashMap<>();
    keyword.forEach((context, map) -> frozen.put(context, freezeLists(map)));
    this.byKeyword = Collections.unmodifiableMap(frozen);
    this.slotFirst = freezeLists(slot);
  }

  /** Returns the dialect name. */
  public String name() {
    return name;
  }

  /** Returns the token that starts comment lines, if the dialect has one. */
  public Optional<String> commentMarker() {
    return commentMarker;
  }

  /** Returns the sorts in declaration order. */
  public Collection<ValueSort> sorts() {
    return sorts.values();
  }

  /** Returns the sort named {@code name}. */
  public Optional<ValueSort> sort(String name) {
    return Optional.ofNullable(sorts.get(name));
  }

  /** Returns the contexts in declaration order. */
  public Collection<Context> contexts() {
    return contexts.values();
  }

  /** Returns the context named {@code name}. */
  public Optional<Context> context(String name) {
    return Optional.ofNullable(contexts.get(name));
  }

  /** Returns the root context. */
  public Context root() {
    return contexts.values().stream().filter(Context::isRoot).findFirst().orElseThrow();
  }

  /** Returns the contexts whose parent is {@code parent}. */
  public List<Context> children(Context parent) {
    return contexts.values().stream()
        .filter(context -> context.parent().equals(Optional.of(parent.name())))
        .toList();
  }

  /** Returns the productions in declaration order. */
  public Collection<Production> productions() {
    return productions.values();
  }

  /** Returns the production named {@code id}. */
  public Optional<Production> production(String id) {
    return Optional.ofNullable(productions.get(id));
  }

  /** Returns the productions of {@code context} in declaration order. */
  public List<Production> productions(Context context) {
    return byContext.getOrDefault(context.name(), List.of());
  }

  /** Returns the matches of {@code tokens} among the productions of {@code context}. */
  public List<Production.Match> match(Context context, List<String> tokens) {
    if (tokens.isEmpty()) {
      return List.of();
    }
    List<Production.Match> matches = new ArrayList<>(1);
    List<Production> keyed =
        byKeyword.getOrDefault(context.name(), Map.of()).getOrDefault(tokens.getFirst(), List.of());
    for (List<Production> candidates :
        List.of(keyed, slotFirst.getOrDefault(context.name(), List.of()))) {
      for (Production production : candidates) {
        production.match(tokens).ifPresent(matches::add);
      }
    }
    return List.copyOf(matches);
  }

  /** Returns whether {@code line} is blank or a comment. */
  public boolean isTrivia(String line) {
    String stripped = line.strip();
    return stripped.isEmpty() || commentMarker.filter(stripped::startsWith).isPresent();
  }

  /** Splits a line into whitespace-separated tokens. */
  public static List<String> tokens(String line) {
    String stripped = line.strip();
    return stripped.isEmpty() ? List.of() : List.of(WHITESPACE.split(stripped));
  }

  /**
   * Parses one configuration into statements, one per line that is neither blank nor a comment.
   *
   * <p>Contexts follow the configuration modes of the device, not the indentation: a line belongs
   * to the innermost open context that has a production matching it, and contexts that cannot match
   * it are closed first. A matched statement that opens a context enters it, and one that closes
   * its context leaves it. A line that no open context can match stays in the innermost context
   * without a match, so that repairs can still delete or replace it.
   */
  public List<Statement> parse(RouterId router, ConfigText text) {
    throw new NotImplemented("Grammar.parse");
  }

  @Override
  public String toString() {
    return "Grammar[" + name + ", " + productions.size() + " productions]";
  }

  private static <T> Map<String, T> index(Collection<T> items, Function<T, String> key) {
    Map<String, T> map = new LinkedHashMap<>();
    for (T item : items) {
      if (map.put(key.apply(item), item) != null) {
        throw new IllegalArgumentException("duplicate name " + key.apply(item));
      }
    }
    return Collections.unmodifiableMap(map);
  }

  private static Map<String, List<Production>> freezeLists(Map<String, List<Production>> lists) {
    Map<String, List<Production>> frozen = new LinkedHashMap<>();
    lists.forEach((key, list) -> frozen.put(key, List.copyOf(list)));
    return Collections.unmodifiableMap(frozen);
  }

  /**
   * A configuration mode, such as the interface mode or the BGP address-family mode.
   *
   * @param name the context's name
   * @param parent the enclosing context, or nothing for the root
   */
  public record Context(String name, Optional<String> parent) {
    /** Checks the components. */
    public Context {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(parent, "parent");
    }

    /** Returns whether this is the root context. */
    public boolean isRoot() {
      return parent.isEmpty();
    }
  }
}
