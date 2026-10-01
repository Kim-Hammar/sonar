package se.kth.sonar.prior;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.state.RepairState;

/**
 * Weights first-match policy objects whose first rule makes every later rule unreachable.
 *
 * <p>An extended ACL beginning with {@code deny ip any any} shadows all following entries. The
 * program property is exact rather than a naming convention. Each ACL is an exchangeable
 * observation; a dominated ACL receives the leave-one-out Jeffreys odds of ordinary ACLs against
 * the other dominated ACLs. Other regions retain neutral weight.
 */
public final class FirstMatchDominanceFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private static final double HALF = 0.5;

  private final ContentCache<Map<ProgramObject.Ref, Double>> factors =
      new ContentCache<>(CACHE_CAPACITY);

  @Override
  public String name() {
    return "first-match-dominance";
  }

  @Override
  public double weight(Region region, RepairState state) {
    if (!(region instanceof SiteRegion site) || !site.site().type().equals("acl")) {
      return 1;
    }
    return factors
        .get(state.program().snapshot().id(), () -> factors(state.program()))
        .getOrDefault(site.site(), 1.0);
  }

  private static Map<ProgramObject.Ref, Double> factors(ProgramModel program) {
    List<ProgramObject.Ref> dominated = new ArrayList<>();
    int ordinary = 0;
    for (ProgramObject acl : program.objects("acl")) {
      if (dominated(program, acl)) {
        dominated.add(acl.ref());
      } else {
        ordinary++;
      }
    }
    if (dominated.isEmpty()) {
      return Map.of();
    }
    double odds = (ordinary + HALF) / (dominated.size() - 1 + HALF);
    Map<ProgramObject.Ref, Double> result = new HashMap<>();
    dominated.forEach(ref -> result.put(ref, Math.max(1, odds)));
    return Map.copyOf(result);
  }

  private static boolean dominated(ProgramModel program, ProgramObject acl) {
    List<Statement> rules =
        acl.statements().stream()
            .flatMap(
                span ->
                    program.statements(span.router()).stream()
                        .filter(statement -> statement.span().equals(span)))
            .filter(statement -> statement.match().isPresent())
            .filter(
                statement -> statement.match().orElseThrow().production().id().startsWith("acl-"))
            .sorted(Comparator.comparingInt(FirstMatchDominanceFactor::sequence))
            .toList();
    if (rules.size() < 2) {
      return false;
    }
    var match = rules.getFirst().match().orElseThrow();
    return match.production().id().equals("acl-any")
        && match.values().getOrDefault("action", List.of()).equals(List.of("deny"));
  }

  private static int sequence(Statement statement) {
    try {
      return Integer.parseInt(
          statement
              .match()
              .orElseThrow()
              .values()
              .getOrDefault("sequence", List.of(""))
              .getFirst());
    } catch (NumberFormatException malformed) {
      return Integer.MAX_VALUE;
    }
  }
}
