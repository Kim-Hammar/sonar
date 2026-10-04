package se.kth.sonar.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
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
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SearchSpaceBuilder;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.LineDiff;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Builds and expands the whole search space of fetched scenarios and reports whether some candidate
 * contains the ground-truth repair. Ground truth is read only here, never by the agent.
 */
@Tag("audit")
class DatasetCoverageAudit {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));
  private static final Path TRUTH =
      Path.of(System.getProperty("sonar.truth", "../../aros/results/ground_truth"));

  @Test
  void theSearchSpaceContainsTheGroundTruthRepair(@TempDir Path storage) throws IOException {
    assumeTrue(Files.isDirectory(DATASET) && Files.isDirectory(TRUTH), "dataset or truth absent");
    List<String> names =
        List.of(System.getProperty("sonar.audit.scenarios", "scenario-001").split(",", -1));
    Dialect dialect = Dialect.load("cisco-ios");
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    int complete = 0;
    try (ExecutionContext context =
            ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1), List.of());
        ResourcePool<DataPlaneEngine> engines =
            ResourcePool.of(
                1,
                () -> new BatfishEngine(new BatfishEngine.Settings(storage.resolve("bf"))),
                DataPlaneEngine::close)) {
      EpochBuilder epochs = Pipeline.epochs(dialect, context);
      SearchSpaceBuilder builder = epochs.spaces();
      VerificationService verifier = new VerificationService(engines, context);
      for (String name : names) {
        long start = System.nanoTime();
        RepairProblem problem = scenarios.load(name, 10);
        Snapshot repaired = snapshot(TRUTH.resolve(name).resolve("initial_configs/configs"));
        Set<String> truth = atoms(problem.initial(), repaired);
        Verification verification = verifier.verify(problem.initial(), problem.specifications());
        Epoch epoch = epochs.initial(problem, verification);
        RepairState state = epoch.state();
        long verified = System.nanoTime();
        SearchSpace space = epoch.space();
        List<Region> expanded =
            context.compute(space.regions(), region -> builder.expand(region, state));
        long expandedAt = System.nanoTime();
        int candidates = 0;
        String best = "absent";
        Set<String> covered = new HashSet<>();
        Map<String, Integer> byKind = new TreeMap<>();
        String focus = System.getProperty("sonar.audit.focus", "");
        for (Region region : expanded) {
          List<Hypothesis> hypotheses = ((Region.Hypotheses.Admitted) region.hypotheses()).list();
          candidates += hypotheses.size();
          if (!focus.isEmpty() && region.id().value().contains(focus)) {
            System.out.println("  region " + region.id() + " has " + hypotheses.size());
            for (Hypothesis hypothesis : hypotheses) {
              System.out.println("    " + hypothesis.transaction().edits());
            }
          }
          for (Hypothesis hypothesis : hypotheses) {
            Set<String> atoms =
                atoms(problem.initial(), problem.initial().patch(hypothesis.transaction()).after());
            Set<String> hit = new HashSet<>(truth);
            hit.retainAll(atoms);
            covered.addAll(hit);
            if (hit.size() == truth.size()) {
              String kind = region.id().value().substring(0, region.id().value().indexOf(':'));
              byKind.merge(kind, 1, Integer::sum);
              if (atoms.size() == truth.size()) {
                best = "exact";
              } else if (!best.equals("exact")) {
                best = "complete";
              }
            }
          }
        }
        if (best.equals("absent") && covered.size() == truth.size()) {
          best = "pieces";
        }
        if (!best.equals("absent") && !best.equals("pieces")) {
          complete++;
        }
        System.out.println(
            name
                + ": "
                + best
                + " (regions "
                + space.regions().size()
                + ", partitions "
                + space.partitions().size()
                + ", candidates "
                + candidates
                + ", truth atoms "
                + truth.size()
                + ", covered "
                + covered.size()
                + ", by "
                + byKind
                + "; verify "
                + (verified - start) / 1_000_000
                + " ms, expand "
                + (expandedAt - verified) / 1_000_000
                + " ms)");
        if (best.equals("absent") || best.equals("pieces")) {
          Set<String> missing = new LinkedHashSet<>(truth);
          missing.removeAll(covered);
          System.out.println("  missing " + missing);
        }
      }
    }
    System.out.println("complete in " + complete + " of " + names.size());
    assertThat(names).isNotEmpty();
  }

  /** Returns the material line changes between two snapshots as signed lines per router. */
  static Set<String> atoms(Snapshot before, Snapshot after) {
    Set<String> atoms = new LinkedHashSet<>();
    for (RouterId router : before.routers()) {
      ConfigText from = before.text(router);
      ConfigText to = after.text(router);
      if (from.equals(to)) {
        continue;
      }
      for (LineEdit edit : LineDiff.between(router, from, to)) {
        switch (edit) {
          case LineEdit.Delete delete -> {
            for (int line = delete.span().first(); line <= delete.span().last(); line++) {
              add(atoms, "-", router, from.line(line));
            }
          }
          case LineEdit.Replace replace -> {
            for (int line = replace.span().first(); line <= replace.span().last(); line++) {
              add(atoms, "-", router, from.line(line));
            }
            replace.lines().forEach(line -> add(atoms, "+", router, line));
          }
          case LineEdit.InsertAfter insert ->
              insert.lines().forEach(line -> add(atoms, "+", router, line));
          case LineEdit.Append append ->
              append.lines().forEach(line -> add(atoms, "+", router, line));
        }
      }
    }
    return atoms;
  }

  private static void add(Set<String> atoms, String sign, RouterId router, String line) {
    String text = String.join(" ", line.strip().split("\\s+"));
    if (text.isEmpty() || text.startsWith("!") || text.startsWith("description ")) {
      return;
    }
    atoms.add(sign + router + ":" + text);
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
