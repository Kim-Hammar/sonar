package se.kth.sonar.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.text.RouterId;

class SpecificationSetTest {
  private static final RouterId R1 = RouterId.of("r1");

  private static ForwardingPredicate reach(String prefix) {
    return new ForwardingPredicate.Reachability(R1, Prefix.parse(prefix));
  }

  private static Specification spec(ForwardingPredicate predicate, String label) {
    return new Specification(predicate, Specification.Status.parse(label));
  }

  @Test
  void statusLabelsFollowTheBenchmarkEvaluator() {
    assertThat(Specification.Status.parse("intact"))
        .isEqualTo(new Specification.Status("intact", Specification.Condition.INTACT, true));
    assertThat(Specification.Status.parse("broken_removed"))
        .isEqualTo(
            new Specification.Status("broken_removed", Specification.Condition.BROKEN, true));
    assertThat(Specification.Status.parse("broken_added"))
        .isEqualTo(new Specification.Status("broken_added", Specification.Condition.BROKEN, false));
    assertThat(Specification.Status.parse("broken_removed|intact").expectPresent()).isTrue();
    assertThat(Specification.Status.parse("broken_removed|intact").initial())
        .isEqualTo(Specification.Condition.BROKEN);
    assertThat(Specification.Status.parse("intact_added").expectPresent()).isFalse();
    assertThat(Specification.Status.parse("absent").initial())
        .isEqualTo(Specification.Condition.UNKNOWN);
    assertThat(Specification.Status.parse("absent").expectPresent()).isFalse();
    assertThat(Specification.Status.parse(" Broken_Added ").label()).isEqualTo("Broken_Added");
    assertThat(Specification.Status.parse("").expectPresent()).isTrue();
  }

  @Test
  void deliveryIsForbiddenByARequiredIsolationOrAnUnwantedReachability() {
    Prefix prefix = Prefix.parse("10.0.0.0/24");
    Specification.Status required = Specification.Status.parse("broken_removed");
    Specification.Status unwanted = Specification.Status.parse("broken_added");
    ForwardingPredicate isolation = new ForwardingPredicate.Isolation(R1, prefix);
    ForwardingPredicate reachability = new ForwardingPredicate.Reachability(R1, prefix);
    assertThat(new Specification(isolation, required).forbidsDelivery()).isTrue();
    assertThat(new Specification(isolation, unwanted).forbidsDelivery()).isFalse();
    assertThat(new Specification(reachability, unwanted).forbidsDelivery()).isTrue();
    assertThat(new Specification(reachability, required).forbidsDelivery()).isFalse();
    assertThat(
            new Specification(
                    new ForwardingPredicate.Waypointing(R1, prefix, RouterId.of("r2")), unwanted)
                .forbidsDelivery())
        .isFalse();
    assertThat(new Specification(reachability, required).requiresDelivery()).isTrue();
    assertThat(new Specification(reachability, unwanted).requiresDelivery()).isFalse();
    assertThat(new Specification(isolation, unwanted).requiresDelivery()).isTrue();
    assertThat(new Specification(isolation, required).requiresDelivery()).isFalse();
  }

  @Test
  void precedenceRanksBrokenAboveOtherLabelsAboveIntact() {
    assertThat(Specification.Status.parse("broken_added").precedence()).isEqualTo(2);
    assertThat(Specification.Status.parse("regression").precedence()).isEqualTo(2);
    assertThat(Specification.Status.parse("absent").precedence()).isEqualTo(1);
    assertThat(Specification.Status.parse("intact").precedence()).isZero();
    assertThat(Specification.Status.parse("").precedence()).isZero();
  }

  @Test
  void duplicateRowsKeepTheHighestPrecedenceAndTheEarliestOnTies() {
    ForwardingPredicate first = reach("10.0.0.0/24");
    ForwardingPredicate second = reach("10.0.1.0/24");
    SpecificationSet set =
        SpecificationSet.of(
            List.of(
                spec(first, "intact"),
                spec(second, "broken_removed"),
                spec(first, "broken_removed"),
                spec(first, "intact"),
                spec(second, "broken_added")));
    assertThat(set.size()).isEqualTo(2);
    assertThat(set.find(first).orElseThrow().status().label()).isEqualTo("broken_removed");
    assertThat(set.find(second).orElseThrow().status().label()).isEqualTo("broken_removed");
    assertThat(set.broken()).hasSize(2);
  }

  @Test
  void judgingSortsSpecificationsIntoFixedUnresolvedAndSideEffects() {
    Specification restored = spec(reach("10.0.0.0/24"), "broken_removed");
    Specification stillMissing = spec(reach("10.0.1.0/24"), "broken_removed");
    Specification stillLeaking = spec(reach("10.0.2.0/24"), "broken_added");
    Specification collateral = spec(reach("10.0.3.0/24"), "intact");
    Specification kept = spec(reach("10.0.4.0/24"), "intact");
    Specification unknown = spec(reach("10.0.5.0/24"), "absent");
    SpecificationSet set =
        SpecificationSet.of(
            List.of(restored, stillMissing, stillLeaking, collateral, kept, unknown));
    Verdict verdict =
        set.judge(
            Set.of(
                restored.predicate(),
                stillLeaking.predicate(),
                kept.predicate(),
                unknown.predicate()));
    assertThat(verdict.fixed()).containsExactly(restored);
    assertThat(verdict.unresolved()).containsExactly(stillMissing, stillLeaking);
    assertThat(verdict.sideEffects()).containsExactly(collateral);
  }

  @Test
  void predicatesHaveDistinctKeys() {
    RouterId waypoint = RouterId.of("w");
    Prefix prefix = Prefix.parse("10.0.0.0/24");
    assertThat(new ForwardingPredicate.Waypointing(R1, prefix, waypoint).key())
        .isEqualTo("waypointing|r1|10.0.0.0/24|w");
    assertThat(new ForwardingPredicate.LoadBalancing(R1, prefix, 2).key())
        .isNotEqualTo(new ForwardingPredicate.LoadBalancing(R1, prefix, 3).key());
    assertThat(new ForwardingPredicate.Isolation(R1, prefix).key())
        .isNotEqualTo(new ForwardingPredicate.Reachability(R1, prefix).key());
  }
}
