package se.kth.sonar.batfish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.verify.DataPlaneEngine;

class BatfishEngineConcurrencyTest {
  private static final Path DATASET = Path.of("../dataset/main_dataset");

  @Test
  void twoEnginesAnalyseDifferentScenariosAtOnce(@TempDir Path storage) throws Exception {
    assumeTrue(Files.isDirectory(DATASET), "dataset not fetched");
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    List<String> names = List.of("scenario-021", "scenario-049");
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      List<Future<Verdict>> verdicts =
          names.stream()
              .map(
                  name ->
                      pool.submit(
                          () -> {
                            RepairProblem problem = scenarios.load(name, 100);
                            try (BatfishEngine engine =
                                new BatfishEngine(
                                    new BatfishEngine.Settings(storage.resolve(name)))) {
                              DataPlaneEngine.Observation observation =
                                  engine.analyze(problem.initial(), problem.specifications());
                              return problem.specifications().judge(observation.holding());
                            }
                          }))
              .toList();
      for (int at = 0; at < names.size(); at++) {
        Verdict verdict = verdicts.get(at).get();
        assertThat(verdict.sideEffects()).isEmpty();
        assertThat(verdict.unresolved())
            .containsExactlyInAnyOrderElementsOf(
                scenarios.load(names.get(at), 100).specifications().broken());
      }
    }
  }
}
