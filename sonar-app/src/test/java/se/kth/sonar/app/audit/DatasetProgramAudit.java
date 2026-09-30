package se.kth.sonar.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.text.RouterId;

/**
 * Models every fetched scenario and reports how the violated relations relate to the routers the
 * ground truth changes. Ground truth is read only here, never by the agent.
 */
@Tag("audit")
class DatasetProgramAudit {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));
  private static final Path TRUTH =
      Path.of(System.getProperty("sonar.truth", "../../aros/results/ground_truth"));

  @Test
  void violatedRelationsPointAtFaultyRouters() throws IOException {
    assumeTrue(Files.isDirectory(DATASET) && Files.isDirectory(TRUTH), "dataset or truth absent");
    Dialect dialect = Dialect.load("cisco-ios");
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    Map<String, Integer> byRelation = new TreeMap<>();
    Map<String, Integer> truthByRelation = new TreeMap<>();
    int hits = 0;
    int misses = 0;
    int total = 0;
    try (ExecutionContext context =
        ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1, 0), List.of())) {
      ProgramService service = new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      for (String name : scenarios.scenarios()) {
        Path truth = TRUTH.resolve(name).resolve("initial_configs/configs");
        if (!Files.isDirectory(truth)) {
          continue;
        }
        RepairProblem problem = scenarios.load(name, 10);
        ProgramModel faulty = service.model(problem.initial(), problem.topology());
        Snapshot repaired = snapshot(truth);
        ProgramModel correct = service.model(repaired, problem.topology());
        NavigableSet<RouterId> changed = new TreeSet<>();
        for (RouterId router : problem.initial().routers()) {
          if (!problem.initial().text(router).equals(repaired.text(router))) {
            changed.add(router);
          }
        }
        NavigableSet<RouterId> implicated = new TreeSet<>();
        for (RelationInstance instance : faulty.violated()) {
          if (!correct.instances().contains(instance)) {
            implicated.addAll(instance.routers());
            byRelation.merge(instance.relation() + "/" + strength(instance), 1, Integer::sum);
          }
        }
        for (RelationInstance instance : correct.violated()) {
          truthByRelation.merge(instance.relation() + "/" + strength(instance), 1, Integer::sum);
        }
        total++;
        boolean hit = changed.stream().anyMatch(implicated::contains);
        if (hit) {
          hits++;
        } else {
          misses++;
          System.out.println(
              name + " miss: changed " + changed + ", dangling " + faulty.dangling().size());
        }
      }
    }
    System.out.println(
        "scenarios " + total + ", faulty routers implicated in " + hits + ", not in " + misses);
    System.out.println("new violations in faulty networks by relation: " + byRelation);
    System.out.println("violations remaining in repaired networks: " + truthByRelation);
    assertThat(total).isPositive();
  }

  private static String strength(RelationInstance instance) {
    return instance.violated().stream().anyMatch(r -> r.strength() == Requirement.Strength.HARD)
        ? "hard"
        : "soft";
  }

  private static Snapshot snapshot(Path directory) throws IOException {
    Map<String, String> texts = new TreeMap<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.toList()) {
        texts.put(file.getFileName().toString(), Files.readString(file));
      }
    }
    return Snapshot.of(texts);
  }
}
