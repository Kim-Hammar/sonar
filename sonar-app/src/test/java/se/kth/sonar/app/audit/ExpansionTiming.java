package se.kth.sonar.app.audit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.batfish.BatfishEngine;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.generate.ValueDomains;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

@Tag("audit")
class ExpansionTiming {
  @Test
  void phases(@TempDir Path storage) {
    Path dataset = Path.of("../dataset/main_dataset");
    assumeTrue(Files.isDirectory(dataset));
    Dialect dialect = Dialect.load("cisco-ios");
    RepairProblem problem = new ScenarioRepository(dataset).load("scenario-001", 10);
    try (ExecutionContext context =
            ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1, 0), List.of());
        ResourcePool<DataPlaneEngine> engines =
            ResourcePool.of(
                1,
                () -> new BatfishEngine(new BatfishEngine.Settings(storage.resolve("bf"))),
                DataPlaneEngine::close)) {
      ProgramService programs =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      Bounds bounds = Bounds.defaults();
      SeedGenerator seeds =
          new SeedGenerator(dialect.schema(), new ValueDomains(dialect.schema(), bounds), bounds);
      ClosureEngine closure = new ClosureEngine(programs, seeds, bounds);
      Admissibility admissibility = new Admissibility(programs, context);
      SearchSpaceBuilder builder =
          new SearchSpaceBuilder(
              seeds, closure, admissibility, new SearchSpace.Capacity(16, 64), bounds, context);
      Verification verification =
          new VerificationService(engines, context)
              .verify(problem.initial(), problem.specifications());
      RepairState state =
          RepairState.initial(
              problem, verification, programs.model(problem.initial(), problem.topology()));
      SearchSpace space = builder.build(state);
      List<SiteRegion> sites =
          space.regions().stream()
              .filter(r -> r instanceof SiteRegion)
              .map(r -> (SiteRegion) r)
              .toList();
      long start = System.nanoTime();
      List<List<List<EditOperator>>> allSeeds =
          context.compute(
              sites, site -> seeds.seeds(state.program(), state.verification(), site.site()));
      long seeded = System.nanoTime();
      int seedCount = allSeeds.stream().mapToInt(List::size).sum();
      List<List<EditOperator>> flat = new ArrayList<>();
      allSeeds.forEach(flat::addAll);
      List<List<Hypothesis>> closed =
          context.compute(
              flat, seed -> closure.close(state.program(), state.verification(), seed, Set.of()));
      long closedAt = System.nanoTime();
      List<Hypothesis> hypotheses = new ArrayList<>();
      closed.forEach(hypotheses::addAll);
      List<Hypothesis> admitted = admissibility.admit(state, hypotheses);
      long admittedAt = System.nanoTime();
      System.out.println(
          "sites "
              + sites.size()
              + ", seeds "
              + seedCount
              + " in "
              + (seeded - start) / 1_000_000
              + " ms; hypotheses "
              + hypotheses.size()
              + " in "
              + (closedAt - seeded) / 1_000_000
              + " ms; admitted "
              + admitted.size()
              + " in "
              + (admittedAt - closedAt) / 1_000_000
              + " ms");
    }
  }
}
