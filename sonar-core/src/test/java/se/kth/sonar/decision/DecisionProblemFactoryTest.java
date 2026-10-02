package se.kth.sonar.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.TransitionLedger;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

class DecisionProblemFactoryTest {
  private static final Dialect DIALECT = Dialect.load("cisco-ios");
  private static final RouterId R1 = RouterId.of("1_as65001_r1");
  private static final RouterId R2 = RouterId.of("2_as65001_r2");
  private static final Specification TARGET =
      new Specification(
          new ForwardingPredicate.Reachability(R1, Prefix.parse("10.0.0.2/32")),
          Specification.Status.parse("broken_removed"));
  private static ExecutionContext context;
  private static RepairState state;

  private static String config(int n, int remoteAs) {
    return """
        hostname r%d
        interface Loopback0
         ip address 10.0.0.%d 255.255.255.255
        router bgp 65001
         neighbor 10.0.0.%d remote-as %d
        """
        .formatted(n, n, 3 - n, remoteAs);
  }

  @BeforeAll
  static void build() {
    context = ExecutionContext.open(ParallelismConfig.sequential(), List.of());
    ProgramService programs = new ProgramService(DIALECT, new CiscoIosSemantics(DIALECT), context);
    Snapshot snapshot =
        Snapshot.of(Map.of(R1 + ".cfg", config(1, 65001), R2 + ".cfg", config(2, 65009)));
    Topology topology = Topology.of(snapshot.routers(), List.of());
    RepairProblem problem =
        new RepairProblem("pair", snapshot, topology, SpecificationSet.of(List.of(TARGET)));
    DataPlaneEvidence evidence =
        new DataPlaneEvidence(
            Map.of(
                TARGET.predicate(),
                List.of(
                    new DataPlaneEvidence.Trace(
                        List.of(R1), DataPlaneEvidence.Disposition.NO_ROUTE))),
            Map.of(Prefix.parse("10.0.0.2/32"), Set.of(R2)),
            Map.of(Prefix.parse("10.0.0.2/32"), Set.of(R2)));
    Verification verification =
        new Verification(
            snapshot.id(), new Verdict(List.of(TARGET), List.of(), List.of()), evidence);
    state = RepairState.initial(problem, verification, programs.model(snapshot, topology));
  }

  @AfterAll
  static void close() {
    context.close();
  }

  private static Hypothesis fix() {
    return Hypothesis.of(
        state.snapshot(),
        List.of(
            new EditOperator.ReplaceSlots(
                state.program().statements(R2).getLast(),
                "asn",
                List.of("65001"),
                new Provenance.Forced("bgp-session", requirement()))));
  }

  private static se.kth.sonar.relation.Requirement requirement() {
    return state.program().violated().getFirst().violated().getFirst();
  }

  private static SearchSpace space() {
    RelationInstance session = state.program().violated().getFirst();
    Region relation =
        new RelationRegion(
            new Region.Id("relation:session"),
            session,
            List.of(TARGET),
            Map.of(),
            new Region.Hypotheses.Admitted(List.of(fix())));
    Region site =
        new SiteRegion(
            new Region.Id("site:r2"),
            new ProgramObject.Ref("bgp-neighbor", R2, List.of("10.0.0.1")),
            List.of(TARGET),
            Map.of(),
            new Region.Hypotheses.Deferred(5));
    return SearchSpace.partitioned(List.of(relation, site), new SearchSpace.Capacity(4, 20));
  }

  @Test
  void aRegionChoiceDescribesTheSettingEvidenceExcerptsAndChoices() {
    SearchSpace space = space();
    Partition partition = space.partitions().getFirst();
    DecisionProblem.RegionChoice question =
        new DecisionProblemFactory(60_000).regionChoice(state, space, partition, List.of());
    List<String> titles = question.context().sections().stream().map(s -> s.title()).toList();
    assertThat(titles)
        .containsExactly(
            "Setting",
            "Violated specifications",
            "Verifier evidence",
            "Configuration excerpts",
            "Program relations");
    assertThat(question.context().sections().get(1).body())
        .contains("1 violated: 1 reachability")
        .contains("1_as65001_r1 reaches 10.0.0.2/32");
    assertThat(question.context().sections().get(2).body()).contains("1_as65001_r1 (no route)");
    assertThat(question.options())
        .containsKeys("relation:session", "site:r2")
        .satisfies(
            options -> {
              assertThat(options.get("relation:session"))
                  .contains("complete bgp-session")
                  .contains("hard equal local.effective-remote-as remote.router.bgp-asn")
                  .contains("1 candidates")
                  .contains("replace `neighbor 10.0.0.1 remote-as 65009`");
              assertThat(options.get("site:r2"))
                  .contains("edit bgp-neighbor 10.0.0.1 on 2_as65001_r2")
                  .contains("neighbor 10.0.0.1 remote-as 65009")
                  .contains("up to 5 candidates");
            });
    assertThat(question.size()).isLessThan(60_000);
  }

  @Test
  void aSpecificationExpectedAbsentIsDescribedAsTheOppositeObligation() {
    Specification forbidden =
        new Specification(
            new ForwardingPredicate.Waypointing(R1, Prefix.parse("10.0.0.2/32"), R2),
            Specification.Status.parse("broken_added"));
    Specification required =
        new Specification(
            new ForwardingPredicate.Waypointing(R2, Prefix.parse("10.0.0.1/32"), R1),
            Specification.Status.parse("broken_removed"));
    Region region =
        new SiteRegion(
            new Region.Id("site:r2"),
            new ProgramObject.Ref("bgp-neighbor", R2, List.of("10.0.0.1")),
            List.of(forbidden, required),
            Map.of(),
            new Region.Hypotheses.Admitted(List.of(fix())));
    DecisionProblem.HypothesisChoice question =
        new DecisionProblemFactory(60_000).hypothesisChoice(state, region, List.of());
    assertThat(question.context().sections().get(1).body())
        .contains("1_as65001_r1 must not reach 10.0.0.2/32 via 2_as65001_r2")
        .contains("2_as65001_r2 reaches 10.0.0.1/32 via 1_as65001_r1");
  }

  @Test
  void aHypothesisChoiceDescribesEachEditAndWhatItRestores() {
    SearchSpace space = space();
    Region region = space.region(new Region.Id("relation:session")).orElseThrow();
    TransitionLedger.Transition tried =
        new TransitionLedger.Transition(
            0, region.id(), fix(), state.verification(), Verdict.Change.NO_EFFECT);
    DecisionProblem.HypothesisChoice question =
        new DecisionProblemFactory(60_000).hypothesisChoice(state, region, List.of(tried));
    assertThat(question.options()).hasSize(1);
    String option = question.options().values().iterator().next();
    assertThat(option)
        .startsWith("on 2_as65001_r2 replace `neighbor 10.0.0.1 remote-as 65009` with")
        .contains("restores bgp-session hard equal");
    assertThat(question.context().sections().getLast().title())
        .isEqualTo("Candidates already verified in this state");
    assertThat(question.context().sections().getLast().body()).contains("no effect");
  }

  @Test
  void questionsShrinkTowardsThePayloadLimit() {
    SearchSpace space = space();
    Partition partition = space.partitions().getFirst();
    DecisionProblemFactory generous = new DecisionProblemFactory(60_000);
    DecisionProblemFactory strict = new DecisionProblemFactory(700);
    int full = generous.regionChoice(state, space, partition, List.of()).size();
    DecisionProblem.RegionChoice small = strict.regionChoice(state, space, partition, List.of());
    assertThat(small.size()).isLessThan(full);
    assertThat(small.context().sections().stream().map(s -> s.title()).toList())
        .doesNotContain("Configuration excerpts", "Program relations");
  }
}
