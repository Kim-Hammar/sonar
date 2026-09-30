package se.kth.sonar.app.audit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.batfish.BatfishEngine;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.DecisionProblemFactory;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.generate.ValueDomains;
import se.kth.sonar.jev.JevRequestCodec;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.prior.DropFactor;
import se.kth.sonar.prior.FrontierFactor;
import se.kth.sonar.prior.PriorFactor;
import se.kth.sonar.prior.RegionPrior;
import se.kth.sonar.prior.RelationFactor;
import se.kth.sonar.prior.SpectrumFactor;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Prints the prior over the regions of one scenario, factor by factor, optionally after applying
 * one line replacement given as {@code sonar.probe.edit=router:line:text}.
 */
@Tag("audit")
class PriorProbe {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));
  private static final int SHOWN = 25;
  private static final int PAYLOAD_LIMIT = 60_000;

  @Test
  void printThePrior(@TempDir Path storage) throws IOException {
    assumeTrue(Files.isDirectory(DATASET), "dataset absent");
    String name = System.getProperty("sonar.audit.scenarios", "scenario-001");
    String edit = System.getProperty("sonar.probe.edit", "");
    Dialect dialect = Dialect.load("cisco-ios");
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    try (ExecutionContext context =
            ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1, 0), List.of());
        ResourcePool<DataPlaneEngine> engines =
            ResourcePool.of(
                1,
                () -> new BatfishEngine(new BatfishEngine.Settings(storage.resolve("bf"))),
                DataPlaneEngine::close)) {
      ProgramService programs =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      VerificationService verifier = new VerificationService(engines, context);
      Bounds bounds = Bounds.defaults();
      SeedGenerator seeds =
          new SeedGenerator(dialect.schema(), new ValueDomains(dialect.schema(), bounds), bounds);
      SearchSpaceBuilder builder =
          new SearchSpaceBuilder(
              seeds,
              new ClosureEngine(programs, seeds, bounds),
              new Admissibility(programs, context),
              new SearchSpace.Capacity(16, 64),
              bounds,
              context);
      RepairProblem problem = scenarios.load(name, 100);
      Snapshot snapshot = problem.initial();
      if (!edit.isEmpty()) {
        String[] parts = edit.split(":", 3);
        RouterId router = RouterId.of(parts[0]);
        int line = Integer.parseInt(parts[1]);
        snapshot =
            snapshot
                .patch(
                    Transaction.of(
                        snapshot,
                        List.of(
                            new LineEdit.Replace(
                                new SourceSpan(router, line, line), List.of(parts[2])))))
                .after();
      }
      Verification verification = verifier.verify(snapshot, problem.specifications());
      RepairState state =
          RepairState.initial(problem, verification, programs.model(snapshot, problem.topology()));
      System.out.println(
          "unresolved "
              + verification.verdict().unresolved().size()
              + ", side effects "
              + verification.verdict().sideEffects().size());
      SearchSpace space = builder.build(state);
      Map<String, PriorFactor> factors =
          Map.of(
              "spectrum", new SpectrumFactor(),
              "relation", new RelationFactor(programs.evaluator()),
              "frontier", new FrontierFactor(),
              "drop", new DropFactor());
      Distribution<Region.Id> prior =
          new RegionPrior(new ArrayList<>(factors.values())).over(space, state);
      List<Region> ranked = new ArrayList<>(space.regions());
      ranked.sort(Comparator.comparingDouble((Region r) -> -prior.probability(r.id())));
      for (Region region : ranked.subList(0, Math.min(SHOWN, ranked.size()))) {
        StringBuilder line = new StringBuilder();
        line.append(String.format("%.4f", prior.probability(region.id())))
            .append("  ")
            .append(region.id())
            .append("  targets ")
            .append(region.targets().size());
        factors.forEach(
            (label, factor) ->
                line.append("  ")
                    .append(label)
                    .append(' ')
                    .append(String.format("%.3f", factor.weight(region, state))));
        System.out.println(line);
      }
      Region best = ranked.getFirst();
      Partition partition = space.partitionOf(best.id());
      SearchSpace expanded = space;
      for (Region.Id id : partition.regions()) {
        expanded = expanded.replace(builder.expand(expanded.region(id).orElseThrow(), state));
      }
      DecisionProblem.RegionChoice question =
          new DecisionProblemFactory(PAYLOAD_LIMIT)
              .regionChoice(state, expanded, partition, List.of());
      System.out.println("question of " + question.size() + " characters");
      for (DecisionProblem.Context.Section section : question.context().sections()) {
        System.out.println("## " + section.title());
        System.out.println(section.body());
      }
      System.out.println("## Options");
      question.options().forEach((id, text) -> System.out.println(id + ": " + text));
      System.out.println(
          "request " + new JevRequestCodec("jev").encode(question).body().length() + " characters");
    }
  }
}
