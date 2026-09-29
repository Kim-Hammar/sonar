package se.kth.sonar.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductionTest {
  private static final Grammar.Context ROOT = new Grammar.Context("root", Optional.empty());
  private static final Grammar.Context ROUTE_MAP =
      new Grammar.Context("route-map", Optional.of("root"));
  private static final ValueSort ASN =
      new ValueSort("asn", new Lexeme.IntegerRange(1, 4_294_967_295L));
  private static final ValueSort COMMUNITY = new ValueSort("community", new Lexeme.Community());
  private static final ValueSort ADDRESS = new ValueSort("address", new Lexeme.Address());
  private static final ValueSort WORD = new ValueSort("word", new Lexeme.Name());

  private static Symbol keyword(String text) {
    return new Symbol.Keyword(text);
  }

  private static Symbol slot(String name, ValueSort sort) {
    return new Symbol.Slot(name, sort, false);
  }

  private static Symbol slots(String name, ValueSort sort) {
    return new Symbol.Slot(name, sort, true);
  }

  private static Production production(String id, Grammar.Context context, Symbol... symbols) {
    return new Production(id, context, List.of(symbols), Optional.empty(), false);
  }

  private static final Production PREPEND =
      production(
          "prepend",
          ROUTE_MAP,
          keyword("set"),
          keyword("as-path"),
          keyword("prepend"),
          slots("asns", ASN));
  private static final Production SET_COMMUNITY =
      production(
          "set-community",
          ROUTE_MAP,
          keyword("set"),
          keyword("community"),
          slots("values", COMMUNITY),
          keyword("additive"));

  @Test
  void matchesFixedAndRepeatedSlots() {
    Production.Match prepend =
        PREPEND.match(Grammar.tokens("set as-path prepend 65001 65001 65002")).orElseThrow();
    assertThat(prepend.values("asns")).containsExactly("65001", "65001", "65002");
    Production.Match community =
        SET_COMMUNITY
            .match(Grammar.tokens("set community 65000:1 no-export additive"))
            .orElseThrow();
    assertThat(community.values("values")).containsExactly("65000:1", "no-export");
    assertThat(PREPEND.variableLength()).isTrue();
    assertThat(PREPEND.minLength()).isEqualTo(4);
  }

  @Test
  void rejectsTokensThatDoNotFit() {
    assertThat(PREPEND.match(Grammar.tokens("set as-path prepend"))).isEmpty();
    assertThat(PREPEND.match(Grammar.tokens("set as-path prepend 0"))).isEmpty();
    assertThat(SET_COMMUNITY.match(Grammar.tokens("set community 65000:1"))).isEmpty();
    assertThat(SET_COMMUNITY.match(Grammar.tokens("set community additive"))).isEmpty();
  }

  @Test
  void renderingInvertsMatching() {
    String line = "set community 65000:1 no-export additive";
    Production.Match match = SET_COMMUNITY.match(Grammar.tokens(line)).orElseThrow();
    assertThat(match.render()).isEqualTo(line);
    assertThat(match.withValues("values", List.of("no-advertise")).render())
        .isEqualTo("set community no-advertise additive");
    assertThat(match.withValues("values", List.of("no-advertise")).value("values"))
        .isEqualTo("no-advertise");
    assertThatThrownBy(() -> match.value("values")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> match.values("missing")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void renderingRejectsValuesThatDoNotFit() {
    assertThatThrownBy(() -> PREPEND.render(Map.of("asns", List.of("x"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PREPEND.render(Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PREPEND.render(Map.of("asns", List.of("1"), "other", List.of("2"))))
        .isInstanceOf(IllegalArgumentException.class);
    Production single = production("single", ROOT, keyword("x"), slot("asn", ASN));
    assertThatThrownBy(() -> single.render(Map.of("asn", List.of("1", "2"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overlapDetectsProductionsThatCanMatchOneLine() {
    Production byAddress =
        production("a", ROUTE_MAP, keyword("neighbor"), slot("peer", ADDRESS), keyword("activate"));
    Production byName =
        production("b", ROUTE_MAP, keyword("neighbor"), slot("group", WORD), keyword("activate"));
    Production wellKnown =
        production(
            "c",
            ROUTE_MAP,
            keyword("set"),
            keyword("community"),
            keyword("no-export"),
            keyword("additive"));
    Production open =
        production(
            "d", ROUTE_MAP, keyword("set"), keyword("community"), slots("values", COMMUNITY));
    assertThat(byAddress.overlaps(byName)).isFalse();
    assertThat(SET_COMMUNITY.overlaps(wellKnown)).isTrue();
    assertThat(wellKnown.overlaps(SET_COMMUNITY)).isTrue();
    assertThat(open.overlaps(SET_COMMUNITY)).isFalse();
    assertThat(PREPEND.overlaps(SET_COMMUNITY)).isFalse();
  }

  @Test
  void constructionRejectsInvalidShapes() {
    assertThatThrownBy(() -> production("x", ROOT, slots("a", ASN), slots("b", ASN)))
        .hasMessageContaining("two repeated slots");
    assertThatThrownBy(() -> production("x", ROOT, slot("a", ASN), slot("a", ASN)))
        .hasMessageContaining("repeats slot");
    assertThatThrownBy(
            () ->
                new Production(
                    "x", ROUTE_MAP, List.of(keyword("x")), Optional.of(ROUTE_MAP), false))
        .hasMessageContaining("not a child");
    assertThatThrownBy(
            () -> new Production("x", ROOT, List.of(keyword("x")), Optional.empty(), true))
        .hasMessageContaining("root");
    assertThatThrownBy(() -> production("x", ROOT)).isInstanceOf(IllegalArgumentException.class);
  }
}
