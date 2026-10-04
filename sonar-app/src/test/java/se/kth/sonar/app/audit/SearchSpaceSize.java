package se.kth.sonar.app.audit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.Pipeline;
import se.kth.sonar.batfish.BatfishEngine;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.engine.Epoch;
import se.kth.sonar.engine.EpochBuilder;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.generate.ValueDomains;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.VerificationService;

/**
 * Measures the initial search space of every scenario. It writes {@code networks.csv} to the
 * directory {@code sonar.audit.output}, with one row per scenario giving the routers, configuration
 * lines, program objects, relation instances, specifications, violated specifications and regions
 * of each kind. It then draws {@code sonar.audit.sample} regions uniformly from all scenarios, with
 * the seed {@code sonar.audit.seed}, generates their candidates, and writes one row per region to
 * {@code regions.csv}, both within the run's limit on candidates per region and without it.
 * Scenarios are measured {@code sonar.audit.workers} at a time.
 */
@Tag("audit")
class SearchSpaceSize {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));
  private static final List<String> KINDS =
      List.of("SiteRegion", "RelationRegion", "IncidenceRegion", "MeshRegion", "TransferRegion");

  private record Sampled(String scenario, Region.Id region) {}

  private interface Job {
    void run(String name) throws IOException;
  }

  @Test
  void measure(@TempDir Path storage) throws Exception {
    assumeTrue(Files.isDirectory(DATASET), "dataset absent");
    Path output = Path.of(System.getProperty("sonar.audit.output", "build/search-space"));
    Files.createDirectories(output);
    int percent = Integer.getInteger("sonar.probe.specifications", 100);
    int sample = Integer.getInteger("sonar.audit.sample", 50);
    int workers = Integer.getInteger("sonar.audit.workers", 4);
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    List<String> chosen =
        scenarios.scenarios().stream()
            .filter(name -> scenarios.specificationPercents(name).contains(percent))
            .toList();
    Dialect dialect = Dialect.load("cisco-ios");
    try (ExecutionContext context =
            ExecutionContext.open(
                new ParallelismConfig(1, 1, Runtime.getRuntime().availableProcessors(), 1),
                List.of());
        ResourcePool<DataPlaneEngine> engines =
            ResourcePool.of(
                workers,
                () ->
                    new BatfishEngine(
                        new BatfishEngine.Settings(storage.resolve("bf-" + System.nanoTime()))),
                DataPlaneEngine::close);
        ExecutorService pool = Executors.newFixedThreadPool(workers)) {
      EpochBuilder epochs = Pipeline.epochs(dialect, context);
      Bounds limits = Bounds.defaults();
      Bounds unlimited =
          new Bounds(
              limits.maxOperators(),
              limits.maxRouters(),
              Integer.MAX_VALUE,
              limits.maxValuesPerSlot(),
              limits.maxClosureDepth());
      SeedGenerator seeds =
          new SeedGenerator(
              dialect.schema(), new ValueDomains(dialect.schema(), unlimited), unlimited);
      SearchSpaceBuilder uncapped =
          new SearchSpaceBuilder(
              seeds,
              new ClosureEngine(epochs.programs(), seeds, unlimited),
              new Admissibility(epochs.programs(), context),
              new SearchSpace.Capacity(Integer.MAX_VALUE, Integer.MAX_VALUE),
              unlimited,
              context);
      VerificationService verifier = new VerificationService(engines, context);
      Map<String, String> rows = new ConcurrentHashMap<>();
      Map<String, List<Region.Id>> regionsOf = new ConcurrentHashMap<>();
      all(
          pool,
          chosen,
          name -> {
            Epoch epoch = epoch(name, percent, scenarios, verifier, epochs);
            regionsOf.put(name, epoch.space().regions().stream().map(Region::id).toList());
            rows.put(name, row(name, epoch));
            System.out.println(rows.get(name));
          });
      try (PrintWriter csv =
          new PrintWriter(Files.newBufferedWriter(output.resolve("networks.csv")))) {
        StringBuilder columns =
            new StringBuilder(
                "scenario,routers,lines,objects,relations,specifications,violated,regions");
        KINDS.forEach(kind -> columns.append(',').append(kind).append('s'));
        csv.println(columns);
        chosen.stream().filter(rows::containsKey).map(rows::get).forEach(csv::println);
      }

      List<Sampled> population = new ArrayList<>();
      for (String name : chosen) {
        for (Region.Id id : regionsOf.getOrDefault(name, List.of())) {
          population.add(new Sampled(name, id));
        }
      }
      Collections.shuffle(population, new Random(Long.getLong("sonar.audit.seed", 1)));
      Map<String, List<Region.Id>> drawn = new TreeMap<>();
      for (Sampled sampled : population.subList(0, Math.min(sample, population.size()))) {
        drawn.computeIfAbsent(sampled.scenario(), name -> new ArrayList<>()).add(sampled.region());
      }
      List<String> regionRows = Collections.synchronizedList(new ArrayList<>());
      all(
          pool,
          List.copyOf(drawn.keySet()),
          name -> {
            Epoch epoch = epoch(name, percent, scenarios, verifier, epochs);
            for (Region.Id id : drawn.get(name)) {
              Region region = epoch.space().region(id).orElseThrow();
              long start = System.nanoTime();
              int candidates = uncapped.expand(region, epoch.state()).hypotheses().bound();
              String line =
                  name
                      + ','
                      + region.getClass().getSimpleName()
                      + ','
                      + Math.min(candidates, limits.maxHypothesesPerRegion())
                      + ','
                      + candidates
                      + ','
                      + (System.nanoTime() - start) / 1_000_000
                      + ','
                      + id.value().replace(',', ';');
              regionRows.add(line);
              System.out.println(line);
            }
          });
      try (PrintWriter csv =
          new PrintWriter(Files.newBufferedWriter(output.resolve("regions.csv")))) {
        csv.println("scenario,kind,candidates,uncapped,milliseconds,region");
        regionRows.stream().sorted().forEach(csv::println);
      }
    }
  }

  private static void all(ExecutorService pool, List<String> names, Job job)
      throws InterruptedException {
    List<Future<?>> futures = new ArrayList<>();
    for (String name : names) {
      futures.add(
          pool.submit(
              () -> {
                job.run(name);
                return null;
              }));
    }
    for (int index = 0; index < futures.size(); index++) {
      try {
        futures.get(index).get();
      } catch (ExecutionException e) {
        System.out.println(names.get(index) + " failed: " + e.getCause());
      }
    }
  }

  private static Epoch epoch(
      String name,
      int percent,
      ScenarioRepository scenarios,
      VerificationService verifier,
      EpochBuilder epochs) {
    RepairProblem problem = scenarios.load(name, percent);
    return epochs.initial(problem, verifier.verify(problem.initial(), problem.specifications()));
  }

  private static String row(String name, Epoch epoch) {
    Snapshot snapshot = epoch.state().snapshot();
    int lines = 0;
    for (RouterId router : snapshot.routers()) {
      lines += snapshot.text(router).lineCount();
    }
    Map<String, Integer> regions = new TreeMap<>();
    for (Region region : epoch.space().regions()) {
      regions.merge(region.getClass().getSimpleName(), 1, Integer::sum);
    }
    StringBuilder row =
        new StringBuilder(name)
            .append(',')
            .append(snapshot.routers().size())
            .append(',')
            .append(lines)
            .append(',')
            .append(epoch.state().program().objects().size())
            .append(',')
            .append(epoch.state().program().instances().size())
            .append(',')
            .append(epoch.state().problem().specifications().size())
            .append(',')
            .append(epoch.state().verification().verdict().unresolved().size())
            .append(',')
            .append(epoch.space().regions().size());
    KINDS.forEach(kind -> row.append(',').append(regions.getOrDefault(kind, 0)));
    return row.toString();
  }
}
