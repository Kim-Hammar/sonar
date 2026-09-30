package se.kth.sonar.app.audit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.Draft;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.generate.ValueDomains;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

/**
 * Prints the seeds of one site ({@code sonar.probe.site=type:router:key}) of a scenario, and for
 * each seed the violations it leaves among the instances it affects and the hypotheses the closure
 * makes of it. Needs no verifier: every broken specification counts as unresolved.
 */
@Tag("audit")
class ClosureProbe {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));

  @Test
  void printTheClosure() throws IOException {
    assumeTrue(Files.isDirectory(DATASET), "dataset absent");
    String name = System.getProperty("sonar.audit.scenarios", "scenario-004");
    String siteText = System.getProperty("sonar.probe.site", "");
    assumeTrue(!siteText.isEmpty(), "no site given");
    Dialect dialect = Dialect.load("cisco-ios");
    try (ExecutionContext context =
        ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1, 0), List.of())) {
      ProgramService programs =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      Bounds bounds = Bounds.defaults();
      SeedGenerator seeds =
          new SeedGenerator(dialect.schema(), new ValueDomains(dialect.schema(), bounds), bounds);
      ClosureEngine closure = new ClosureEngine(programs, seeds, bounds);
      RepairProblem problem = new ScenarioRepository(DATASET).load(name, 100);
      Verification verification =
          new Verification(
              problem.initial().id(),
              new Verdict(problem.specifications().broken(), List.of(), List.of()),
              DataPlaneEvidence.none());
      RepairState state =
          RepairState.initial(
              problem, verification, programs.model(problem.initial(), problem.topology()));
      ProgramObject.Ref site = ProgramObject.Ref.parse(siteText);
      for (ProgramObject object : state.program().objectsOf(site.router())) {
        if (object.ref().type().equals("bgp-neighbor") || object.ref().equals(site)) {
          System.out.println("object " + object.ref() + " " + object.attributes());
        }
      }
      for (RelationInstance instance : state.program().instances("bgp-session")) {
        if (instance.routers().contains(site.router())) {
          System.out.println(
              "session " + instance.roles() + " violated " + instance.violated().size());
        }
      }
      Set<String> baseline = violations(state.program().violated());
      for (List<EditOperator> seed : seeds.seeds(state.program(), verification, site)) {
        System.out.println("seed " + seed);
        Draft draft = Draft.of(programs, state.program()).with(seed);
        Set<ProgramObject.Ref> touched = draft.touched();
        for (RelationInstance instance : draft.program().violated()) {
          if (!instance.dependsOn(touched)) {
            continue;
          }
          for (Requirement requirement : instance.violated()) {
            String key = instance.relation() + " " + instance.roles() + " " + requirement.text();
            System.out.println("  " + (baseline.contains(key) ? "old " : "NEW ") + key);
          }
        }
        List<Hypothesis> hypotheses = closure.close(state.program(), verification, seed, Set.of());
        for (Hypothesis hypothesis : hypotheses) {
          System.out.println("  => " + hypothesis.transaction().edits());
          Draft result = Draft.of(programs, state.program()).with(hypothesis.operators());
          Set<ProgramObject.Ref> changed = result.touched();
          System.out.println("     touched " + changed);
          for (RelationInstance instance : result.program().violated()) {
            if (!instance.dependsOn(changed)) {
              continue;
            }
            for (Requirement requirement : instance.violated()) {
              String key = instance.relation() + " " + instance.roles() + " " + requirement.text();
              System.out.println("     " + (baseline.contains(key) ? "old " : "NEW ") + key);
            }
          }
        }
        if (hypotheses.isEmpty()) {
          System.out.println("  => nothing");
        }
      }
    }
  }

  private static Set<String> violations(List<RelationInstance> violated) {
    Set<String> keys = new java.util.HashSet<>();
    for (RelationInstance instance : violated) {
      for (Requirement requirement : instance.violated()) {
        keys.add(instance.relation() + " " + instance.roles() + " " + requirement.text());
      }
    }
    return keys;
  }
}
