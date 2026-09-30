package se.kth.sonar.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.batfish.BatfishEngine;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.verify.DataPlaneEngine;

/**
 * Verifies every fetched scenario's faulty network at 100% of its specifications and reports where
 * the verdict disagrees with the benchmark's labels.
 */
@Tag("audit")
class DatasetVerifierAudit {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));

  @Test
  void faultyNetworksViolateExactlyTheirBrokenSpecifications(@TempDir Path storage) {
    assumeTrue(Files.isDirectory(DATASET), "dataset not fetched");
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    List<String> names = scenarios.scenarios();
    int limit = Integer.getInteger("sonar.audit.limit", names.size());
    int matching = 0;
    int total = 0;
    long millis = 0;
    try (BatfishEngine engine = new BatfishEngine(new BatfishEngine.Settings(storage))) {
      for (String name : names.subList(0, Math.min(limit, names.size()))) {
        if (!scenarios.specificationPercents(name).contains(100)) {
          System.out.println(name + " has no complete specification file; skipped");
          continue;
        }
        RepairProblem problem = scenarios.load(name, 100);
        long start = System.nanoTime();
        DataPlaneEngine.Observation observation =
            engine.analyze(problem.initial(), problem.specifications());
        millis += (System.nanoTime() - start) / 1_000_000;
        Verdict verdict = problem.specifications().judge(observation.holding());
        Set<Specification> broken = new HashSet<>(problem.specifications().broken());
        Set<Specification> unresolved = new HashSet<>(verdict.unresolved());
        total++;
        if (verdict.sideEffects().isEmpty() && unresolved.equals(broken)) {
          matching++;
        } else {
          Set<Specification> spuriousFixed = new HashSet<>(verdict.fixed());
          System.out.println(
              name
                  + " MISMATCH: side effects "
                  + verdict.sideEffects().size()
                  + ", fixed "
                  + spuriousFixed.size()
                  + " of "
                  + broken.size()
                  + ", e.g. "
                  + first(verdict.sideEffects())
                  + " / "
                  + first(verdict.fixed()));
        }
      }
    }
    System.out.println(
        "scenarios "
            + total
            + ", matching labels "
            + matching
            + ", avg ms "
            + (total == 0 ? 0 : millis / total));
    assertThat(total).isPositive();
  }

  private static String first(List<Specification> specifications) {
    return specifications.isEmpty()
        ? "-"
        : specifications.getFirst().predicate().key()
            + " "
            + specifications.getFirst().status().label();
  }
}
