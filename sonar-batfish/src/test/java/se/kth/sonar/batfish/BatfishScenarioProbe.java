package se.kth.sonar.batfish;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.verify.DataPlaneEngine;

/**
 * Verifies the faulty network of one scenario ({@code sonar.audit.scenarios}) and prints the
 * verdict.
 */
@Tag("audit")
class BatfishScenarioProbe {
  private static final Path DATASET = Path.of("../dataset/main_dataset");

  @Test
  void verifyOneScenario(@TempDir Path storage) throws IOException {
    assumeTrue(Files.isDirectory(DATASET), "dataset not fetched");
    String name = System.getProperty("sonar.audit.scenarios", "scenario-021");
    RepairProblem problem = new ScenarioRepository(DATASET).load(name, 100);
    try (BatfishEngine engine = new BatfishEngine(new BatfishEngine.Settings(storage))) {
      String focus = System.getProperty("sonar.probe.destination", "");
      DataPlaneEngine.Observation observation =
          engine.analyze(
              problem.initial(),
              problem.specifications(),
              rows -> {
                for (ForwardingRow row : rows) {
                  if (!focus.isEmpty() && row.prefix().toString().equals(focus)) {
                    System.out.println("row " + row);
                  }
                }
              });
      Verdict verdict = problem.specifications().judge(observation.holding());
      for (Specification specification : verdict.unresolved()) {
        if (!focus.isEmpty() && specification.predicate().destination().toString().equals(focus)) {
          System.out.println(
              specification.predicate().key()
                  + " -> "
                  + observation.evidence().traces().get(specification.predicate()));
        }
      }
      System.out.println(
          name
              + ": unresolved "
              + verdict.unresolved().size()
              + ", side effects "
              + verdict.sideEffects().size()
              + ", fixed "
              + verdict.fixed().size());
    }
  }
}
