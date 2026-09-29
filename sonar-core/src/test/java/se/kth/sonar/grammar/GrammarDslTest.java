package se.kth.sonar.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import se.kth.sonar.common.DslException;

class GrammarDslTest {
  private static final String SMALL =
      """
      grammar small
      # a comment line
      comment !

      sort asn integer 1..65535
      sort word name
      sort address ipv4
      context root
      context router-bgp in root
      production router-bgp root opens router-bgp : router bgp <asn:asn>
      production neighbor router-bgp : neighbor <peer:address> remote-as <asn:asn>
      production group router-bgp : neighbor <group:word> remote-as <asn:asn>
      """;

  @Test
  void parsesDeclarations() {
    Grammar grammar = GrammarDsl.parse(SMALL, "small.grammar");
    assertThat(grammar.name()).isEqualTo("small");
    assertThat(grammar.commentMarker()).contains("!");
    assertThat(grammar.root().name()).isEqualTo("root");
    assertThat(grammar.children(grammar.root()))
        .extracting(Grammar.Context::name)
        .containsExactly("router-bgp");
    assertThat(grammar.production("router-bgp").orElseThrow().opens())
        .contains(grammar.context("router-bgp").orElseThrow());
    assertThat(
            grammar.match(
                grammar.context("router-bgp").orElseThrow(),
                Grammar.tokens("neighbor RR remote-as 1")))
        .singleElement()
        .satisfies(match -> assertThat(match.production().id()).isEqualTo("group"));
    assertThat(grammar.isTrivia("  ! banner")).isTrue();
    assertThat(grammar.isTrivia("   ")).isTrue();
    assertThat(grammar.isTrivia("router bgp 1")).isFalse();
  }

  static Stream<Arguments> errors() {
    return Stream.of(
        Arguments.of("sort asn integer 1..2", 1, "expected 'grammar <name>'"),
        Arguments.of(SMALL + "sort real float\n", 13, "unknown lexeme 'float'"),
        Arguments.of(SMALL + "sort backwards integer 5..1\n", 13, "invalid integer range"),
        Arguments.of(SMALL + "sort asn name\n", 13, "sort 'asn' is already declared"),
        Arguments.of(SMALL + "production x root : x <v:nope>\n", 13, "unknown sort 'nope'"),
        Arguments.of(
            SMALL + "production group root : y\n", 13, "production 'group' is already declared"),
        Arguments.of(SMALL + "context other\n", 13, "exactly one root context"),
        Arguments.of(SMALL + "context orphan in nowhere\n", 13, "unknown parent context 'nowhere'"),
        Arguments.of(SMALL + "context idle in root\n", 13, "no production opens context 'idle'"),
        Arguments.of(
            SMALL + "production twice router-bgp : neighbor <x:word> remote-as <y:asn>\n",
            13,
            "can match the same line"),
        Arguments.of(
            SMALL + "production many root : x <a:asn>+ <b:asn>+\n", 13, "two repeated slots"),
        Arguments.of(SMALL + "production broken root : x <asn>\n", 13, "malformed slot"),
        Arguments.of(
            SMALL + "production odd root sometimes : x\n",
            13,
            "unknown production option 'sometimes'"),
        Arguments.of(SMALL + "production nothing root :\n", 13, "has no symbols"),
        Arguments.of(SMALL + "rule x\n", 13, "unknown declaration 'rule'"));
  }

  @ParameterizedTest
  @MethodSource("errors")
  void reportsErrorsAtTheirLine(String source, int line, String message) {
    assertThatThrownBy(() -> GrammarDsl.parse(source, "test.grammar"))
        .isInstanceOfSatisfying(
            DslException.class,
            error -> {
              assertThat(error.origin()).isEqualTo("test.grammar");
              assertThat(error.line()).isEqualTo(line);
              assertThat(error.getMessage())
                  .startsWith("test.grammar:" + line + ": ")
                  .contains(message);
            });
  }
}
