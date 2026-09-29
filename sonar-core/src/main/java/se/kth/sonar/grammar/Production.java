package se.kth.sonar.grammar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One statement form of a dialect: a sequence of keywords and typed slots that may appear in a
 * context. A statement may open a child context, like {@code interface Loopback0}, or close its
 * context, like {@code exit-address-family}.
 */
public final class Production {
  private final String id;
  private final Grammar.Context context;
  private final List<Symbol> symbols;
  private final Optional<Grammar.Context> opens;
  private final boolean closes;
  private final int repeated;
  private final SequencedMap<String, Symbol.Slot> slots;

  /**
   * Creates a production.
   *
   * @throws IllegalArgumentException if there is no symbol, slot names repeat, more than one slot
   *     repeats, the opened context is not a child of {@code context}, or a root statement closes
   */
  public Production(
      String id,
      Grammar.Context context,
      List<Symbol> symbols,
      Optional<Grammar.Context> opens,
      boolean closes) {
    this.id = Objects.requireNonNull(id, "id");
    this.context = Objects.requireNonNull(context, "context");
    this.symbols = List.copyOf(symbols);
    this.opens = Objects.requireNonNull(opens, "opens");
    this.closes = closes;
    if (this.symbols.isEmpty()) {
      throw new IllegalArgumentException("production " + id + " has no symbol");
    }
    SequencedMap<String, Symbol.Slot> byName = new LinkedHashMap<>();
    int repeatedAt = -1;
    for (int index = 0; index < this.symbols.size(); index++) {
      if (this.symbols.get(index) instanceof Symbol.Slot slot) {
        if (byName.put(slot.name(), slot) != null) {
          throw new IllegalArgumentException("production " + id + " repeats slot " + slot.name());
        }
        if (slot.repeated()) {
          if (repeatedAt >= 0) {
            throw new IllegalArgumentException("production " + id + " has two repeated slots");
          }
          repeatedAt = index;
        }
      }
    }
    this.repeated = repeatedAt;
    this.slots = Collections.unmodifiableSequencedMap(byName);
    if (opens.isPresent() && !opens.get().parent().equals(Optional.of(context.name()))) {
      throw new IllegalArgumentException(
          "production "
              + id
              + " opens "
              + opens.get().name()
              + ", which is not a child of "
              + context.name());
    }
    if (closes && context.isRoot()) {
      throw new IllegalArgumentException("production " + id + " cannot close the root context");
    }
  }

  /** Returns the production's name, unique within its grammar. */
  public String id() {
    return id;
  }

  /** Returns the context in which statements of this production appear. */
  public Grammar.Context context() {
    return context;
  }

  /** Returns the keywords and slots in order. */
  public List<Symbol> symbols() {
    return symbols;
  }

  /** Returns the context that statements of this production open, if any. */
  public Optional<Grammar.Context> opens() {
    return opens;
  }

  /** Returns whether statements of this production close their context. */
  public boolean closes() {
    return closes;
  }

  /** Returns the slots in order. */
  public List<Symbol.Slot> slots() {
    return List.copyOf(slots.values());
  }

  /** Returns the slot named {@code name}, if any. */
  public Optional<Symbol.Slot> slot(String name) {
    return Optional.ofNullable(slots.get(name));
  }

  /** Returns the smallest number of tokens a statement of this production has. */
  public int minLength() {
    return symbols.size();
  }

  /** Returns whether statements of this production have a variable number of tokens. */
  public boolean variableLength() {
    return repeated >= 0;
  }

  /** Matches {@code tokens} against this production and returns the slot values if they fit. */
  public Optional<Match> match(List<String> tokens) {
    int extra = tokens.size() - symbols.size();
    if (extra < 0 || (extra > 0 && repeated < 0)) {
      return Optional.empty();
    }
    SequencedMap<String, List<String>> values = new LinkedHashMap<>();
    for (int position = 0; position < tokens.size(); position++) {
      Symbol symbol = symbolAt(position, tokens.size());
      String token = tokens.get(position);
      if (!symbol.accepts(token)) {
        return Optional.empty();
      }
      if (symbol instanceof Symbol.Slot slot) {
        values.computeIfAbsent(slot.name(), name -> new ArrayList<>()).add(token);
      }
    }
    return Optional.of(new Match(this, values));
  }

  /**
   * Renders a statement from slot values.
   *
   * @throws IllegalArgumentException if a slot lacks values, a single slot has several, or a value
   *     does not belong to its slot's sort
   */
  public String render(Map<String, List<String>> values) {
    List<String> tokens = new ArrayList<>();
    for (Symbol symbol : symbols) {
      switch (symbol) {
        case Symbol.Keyword keyword -> tokens.add(keyword.text());
        case Symbol.Slot slot -> tokens.addAll(checkedValues(slot, values.get(slot.name())));
      }
    }
    Set<String> unknown = new HashSet<>(values.keySet());
    unknown.removeAll(slots.keySet());
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("production " + id + " has no slots " + unknown);
    }
    return String.join(" ", tokens);
  }

  /** Returns whether some token sequence matches both productions. */
  public boolean overlaps(Production other) {
    int shortest = Math.max(minLength(), other.minLength());
    int longest;
    if (variableLength() && other.variableLength()) {
      longest = minLength() + other.minLength();
    } else if (variableLength()) {
      longest = other.minLength();
    } else if (other.variableLength()) {
      longest = minLength();
    } else {
      longest = Math.min(minLength(), other.minLength());
    }
    for (int length = shortest; length <= longest; length++) {
      if (overlapsAt(other, length)) {
        return true;
      }
    }
    return false;
  }

  private boolean overlapsAt(Production other, int length) {
    for (int position = 0; position < length; position++) {
      if (!symbolAt(position, length).overlaps(other.symbolAt(position, length))) {
        return false;
      }
    }
    return true;
  }

  private Symbol symbolAt(int position, int length) {
    if (repeated < 0 || position < repeated) {
      return symbols.get(position);
    }
    int suffixStart = length - (symbols.size() - repeated - 1);
    return position < suffixStart
        ? symbols.get(repeated)
        : symbols.get(position - (length - symbols.size()));
  }

  private List<String> checkedValues(Symbol.Slot slot, List<String> given) {
    if (given == null || given.isEmpty()) {
      throw new IllegalArgumentException("slot " + slot.name() + " of " + id + " has no value");
    }
    if (!slot.repeated() && given.size() != 1) {
      throw new IllegalArgumentException("slot " + slot.name() + " of " + id + " takes one value");
    }
    for (String value : given) {
      if (!slot.accepts(value)) {
        throw new IllegalArgumentException(
            "'"
                + value
                + "' is not a "
                + slot.sort().name()
                + " for slot "
                + slot.name()
                + " of "
                + id);
      }
    }
    return given;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Production that
        && id.equals(that.id)
        && context.equals(that.context)
        && symbols.equals(that.symbols)
        && opens.equals(that.opens)
        && closes == that.closes;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, context, symbols, opens, closes);
  }

  @Override
  public String toString() {
    return id + ": " + symbols.stream().map(Symbol::toString).collect(Collectors.joining(" "));
  }

  /**
   * The slot values of a statement that matched a production.
   *
   * @param production the matched production
   * @param values the values of every slot, in slot order
   */
  public record Match(Production production, Map<String, List<String>> values) {
    /** Copies the values. */
    public Match {
      Objects.requireNonNull(production, "production");
      SequencedMap<String, List<String>> copy = new LinkedHashMap<>();
      values.forEach((slot, tokens) -> copy.put(slot, List.copyOf(tokens)));
      values = Collections.unmodifiableSequencedMap(copy);
    }

    /** Returns the single value of {@code slot}. */
    public String value(String slot) {
      List<String> tokens = values(slot);
      if (tokens.size() != 1) {
        throw new IllegalArgumentException("slot " + slot + " has " + tokens.size() + " values");
      }
      return tokens.getFirst();
    }

    /** Returns the values of {@code slot}. */
    public List<String> values(String slot) {
      List<String> tokens = values.get(slot);
      if (tokens == null) {
        throw new IllegalArgumentException(
            "production " + production.id() + " has no slot " + slot);
      }
      return tokens;
    }

    /** Returns this match with the values of {@code slot} replaced. */
    public Match withValues(String slot, List<String> replacement) {
      values(slot);
      SequencedMap<String, List<String>> changed = new LinkedHashMap<>(values);
      changed.put(slot, replacement);
      production.render(changed);
      return new Match(production, changed);
    }

    /** Renders the statement text without indentation. */
    public String render() {
      return production.render(values);
    }
  }
}
