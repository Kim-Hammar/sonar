package se.kth.sonar.app.audit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

@Tag("audit")
class RepairedViolationsDump {
  private static final Path DATASET = Path.of("../dataset/main_dataset");
  private static final Path TRUTH = Path.of("../../aros/results/ground_truth");

  @Test
  void dump() throws IOException {
    assumeTrue(Files.isDirectory(DATASET) && Files.isDirectory(TRUTH));
    Dialect dialect = Dialect.load("cisco-ios");
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    Map<String, Integer> byRequirement = new TreeMap<>();
    Map<String, Integer> byScenario = new TreeMap<>();
    Map<String, String> example = new TreeMap<>();
    try (ExecutionContext context =
        ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1, 0), List.of())) {
      ProgramService service = new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      for (String name : scenarios.scenarios()) {
        Path truth = TRUTH.resolve(name).resolve("initial_configs/configs");
        if (!Files.isDirectory(truth)) {
          continue;
        }
        RepairProblem problem = scenarios.load(name, 10);
        Map<String, String> texts = new TreeMap<>();
        try (Stream<Path> files = Files.list(truth)) {
          for (Path file : files.toList()) {
            texts.put(file.getFileName().toString(), Files.readString(file));
          }
        }
        ProgramModel correct = service.model(Snapshot.of(texts), problem.topology());
        Set<String> seenHere = new HashSet<>();
        for (RelationInstance instance : correct.violated()) {
          for (Requirement requirement : instance.violated()) {
            String key = instance.relation() + " " + requirement.text();
            byRequirement.merge(key, 1, Integer::sum);
            if (seenHere.add(key)) {
              byScenario.merge(key, 1, Integer::sum);
            }
            example.putIfAbsent(key, name + " " + instance.roles());
          }
        }
      }
    }
    byRequirement.forEach(
        (key, count) ->
            System.out.println(
                count
                    + " instances in "
                    + byScenario.get(key)
                    + " scenarios\t"
                    + key
                    + "\n\t\t"
                    + example.get(key)));
  }
}
