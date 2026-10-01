package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.text.SourceSpan;

/**
 * Generates the other bijection between a neighbor's inbound and outbound policy attachments.
 *
 * <p>An attachment pair is one object: each direction has one policy, and exchanging the two
 * observed policies is the only alternative permutation. The transaction changes both statements
 * atomically, so verification never has to infer a repair from two individually incomplete edits.
 */
public final class PolicyAttachmentPermutation {
  private PolicyAttachmentPermutation() {}

  /** Returns the alternative attachment permutation for {@code site}, when it has one. */
  public static List<Hypothesis> generate(ProgramModel program, ProgramObject.Ref site) {
    if (!site.type().equals("bgp-neighbor")) {
      return List.of();
    }
    ProgramObject neighbor = program.object(site).orElse(null);
    if (neighbor == null) {
      return List.of();
    }
    Set<SourceSpan> spans = new HashSet<>(neighbor.statements());
    List<Statement> inbound = new ArrayList<>();
    List<Statement> outbound = new ArrayList<>();
    for (Statement statement : program.statements(site.router())) {
      if (!spans.contains(statement.span())) {
        continue;
      }
      Production.Match match = statement.match().orElse(null);
      if (match == null || !match.production().id().equals("neighbor-route-map")) {
        continue;
      }
      String direction =
          match.values().getOrDefault("direction", List.of()).stream().findFirst().orElse("");
      if (direction.equals("in")) {
        inbound.add(statement);
      } else if (direction.equals("out")) {
        outbound.add(statement);
      }
    }
    if (inbound.size() != 1 || outbound.size() != 1) {
      return List.of();
    }
    String inboundMap = value(inbound.getFirst(), "map");
    String outboundMap = value(outbound.getFirst(), "map");
    if (inboundMap.isEmpty() || outboundMap.isEmpty() || inboundMap.equals(outboundMap)) {
      return List.of();
    }
    Provenance provenance = new Provenance.Inferred("bijective policy attachment permutation");
    return List.of(
        Hypothesis.of(
            program.snapshot(),
            List.of(
                new EditOperator.ReplaceSlots(
                    inbound.getFirst(), "map", List.of(outboundMap), provenance),
                new EditOperator.ReplaceSlots(
                    outbound.getFirst(), "map", List.of(inboundMap), provenance))));
  }

  private static String value(Statement statement, String slot) {
    return statement.match().orElseThrow().values().getOrDefault(slot, List.of()).stream()
        .findFirst()
        .orElse("");
  }
}
