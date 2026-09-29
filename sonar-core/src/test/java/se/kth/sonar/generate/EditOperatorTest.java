package se.kth.sonar.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.DomainRule;
import se.kth.sonar.relation.ValueSupport;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

class EditOperatorTest {
  private static final Dialect CISCO = Dialect.load("cisco-ios");
  private static final Grammar GRAMMAR = CISCO.grammar();
  private static final RouterId R1 = RouterId.of("r1");

  private static Statement statement(int line, String context, String indentation, String text) {
    Grammar.Context mode = GRAMMAR.context(context).orElseThrow();
    Optional<Production.Match> match =
        GRAMMAR.match(mode, Grammar.tokens(text)).stream().findFirst();
    return new Statement(SourceSpan.line(R1, line), mode, line - 1, indentation, text, match);
  }

  @Test
  void replacingASlotKeepsTheIndentationAndTheOtherValues() {
    EditOperator.ReplaceSlot operator =
        new EditOperator.ReplaceSlot(
            statement(2, "interface", " ", "ip ospf 1 area 0"),
            "area",
            List.of("7"),
            new Provenance.Existing());
    assertThat(operator.lower())
        .isEqualTo(new LineEdit.Replace(SourceSpan.line(R1, 2), List.of(" ip ospf 1 area 7")));
    assertThat(operator.router()).isEqualTo(R1);
  }

  @Test
  void replacingASlotRejectsValuesTheSlotCannotTake() {
    Statement cost = statement(2, "interface", " ", "ip ospf cost 10");
    assertThatThrownBy(
            () ->
                new EditOperator.ReplaceSlot(cost, "cost", List.of("0"), new Provenance.Existing()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EditOperator.ReplaceSlot(cost, "area", List.of("1"), new Provenance.Existing()))
        .isInstanceOf(IllegalArgumentException.class);
    Statement unknown = statement(3, "interface", " ", "carrier-delay 10");
    assertThat(unknown.matched()).isFalse();
    assertThatThrownBy(
            () ->
                new EditOperator.ReplaceSlot(unknown, "x", List.of("1"), new Provenance.Existing()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void insertingRendersTheProductionWithItsIndentation() {
    EditOperator.InsertStatement operator =
        new EditOperator.InsertStatement(
            R1,
            7,
            "  ",
            GRAMMAR.production("neighbor-activate").orElseThrow(),
            Map.of("peer", List.of("10.0.0.1")),
            new Provenance.Existing());
    assertThat(operator.lower())
        .isEqualTo(new LineEdit.InsertAfter(R1, 7, List.of("  neighbor 10.0.0.1 activate")));
    assertThatThrownBy(
            () ->
                new EditOperator.InsertStatement(
                    R1,
                    7,
                    "  ",
                    GRAMMAR.production("neighbor-activate").orElseThrow(),
                    Map.of("peer", List.of("RR")),
                    new Provenance.Existing()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deletingRemovesTheStatementLine() {
    Statement shutdown = statement(4, "interface", " ", "shutdown");
    assertThat(new EditOperator.DeleteStatement(shutdown).lower())
        .isEqualTo(new LineEdit.Delete(SourceSpan.line(R1, 4)));
  }

  @Test
  void aHypothesisInventsValuesWhenAnyOperatorDoes() {
    Snapshot base =
        Snapshot.of(Map.of("r1.cfg", "interface GigabitEthernet0/0\n ip ospf cost 10\n"));
    Statement cost = statement(2, "interface", " ", "ip ospf cost 10");
    DomainRule fresh =
        new DomainRule(
            GRAMMAR.production("ip-ospf-cost").orElseThrow(),
            List.of("cost"),
            new DomainRule.Origin.Fresh());
    Hypothesis observed =
        Hypothesis.of(
            base,
            List.of(
                new EditOperator.ReplaceSlot(
                    cost, "cost", List.of("20"), new Provenance.Existing())));
    Hypothesis invented =
        Hypothesis.of(
            base,
            List.of(
                new EditOperator.ReplaceSlot(
                    cost, "cost", List.of("30"), new Provenance.Domain(fresh))));
    assertThat(observed.support()).isEqualTo(ValueSupport.OBSERVED);
    assertThat(invented.support()).isEqualTo(ValueSupport.INVENTED);
    assertThat(observed.routers()).containsExactly(R1);
    assertThat(observed.cost()).isEqualTo(2);
    assertThat(base.patch(observed.transaction()).after().text(R1).text())
        .isEqualTo("interface GigabitEthernet0/0\n ip ospf cost 20\n");
  }
}
