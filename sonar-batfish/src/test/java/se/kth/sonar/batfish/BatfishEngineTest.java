package se.kth.sonar.batfish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.DataPlaneEvidence;

class BatfishEngineTest {
  private static final Path DATASET = Path.of("../dataset/main_dataset");

  @ParameterizedTest
  @ValueSource(ints = {10, 100})
  void theFaultyNetworkViolatesExactlyTheBrokenSpecifications(int percent, @TempDir Path storage)
      throws IOException {
    assumeTrue(Files.isDirectory(DATASET), "dataset not fetched");
    RepairProblem problem = new ScenarioRepository(DATASET).load("scenario-001", percent);
    try (BatfishEngine engine = new BatfishEngine(new BatfishEngine.Settings(storage))) {
      long start = System.nanoTime();
      DataPlaneEngine.Observation observation =
          engine.analyze(problem.initial(), problem.specifications());
      System.out.println(
          percent
              + "%: "
              + problem.specifications().size()
              + " specifications analysed in "
              + (System.nanoTime() - start) / 1_000_000
              + " ms");
      Verdict verdict = problem.specifications().judge(observation.holding());
      assertThat(verdict.sideEffects()).isEmpty();
      assertThat(verdict.fixed()).isEmpty();
      assertThat(verdict.unresolved())
          .containsExactlyInAnyOrderElementsOf(problem.specifications().broken());
      assertThat(observation.evidence().traces().keySet())
          .containsExactlyInAnyOrderElementsOf(
              problem.specifications().all().stream().map(Specification::predicate).toList());
      List<DataPlaneEvidence.Trace> violated =
          observation.evidence().traces().get(verdict.unresolved().getFirst().predicate());
      assertThat(violated)
          .singleElement()
          .satisfies(
              trace -> {
                assertThat(trace.hops())
                    .containsExactly(verdict.unresolved().getFirst().predicate().source());
                assertThat(trace.disposition()).isEqualTo(DataPlaneEvidence.Disposition.NO_ROUTE);
              });
      if (percent == 100) {
        Specification reachable =
            problem.specifications().all().stream()
                .filter(s -> s.predicate() instanceof ForwardingPredicate.Reachability)
                .filter(s -> s.status().initial() == Specification.Condition.INTACT)
                .findFirst()
                .orElseThrow();
        assertThat(observation.evidence().traces().get(reachable.predicate()))
            .isNotEmpty()
            .allMatch(trace -> trace.disposition() == DataPlaneEvidence.Disposition.ACCEPTED);
      }
      assertThat(observation.evidence().routeHolders()).isNotEmpty();
      assertThat(engine.analyze(problem.initial(), problem.specifications()).holding())
          .isEqualTo(observation.holding());
      try (Stream<Path> files = Files.walk(storage)) {
        assertThat(files.filter(path -> path.getFileName().toString().equals("s1"))).isEmpty();
      }
    }
    assertThat(Files.exists(storage)).isFalse();
  }
}
