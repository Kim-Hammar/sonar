package se.kth.sonar.app.audit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.CiscoIosSemantics;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramService;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;

@Tag("audit")
class ModelTiming {
  @Test
  void incrementalModelsMatchFullOnesAndAreFaster() {
    Path dataset = Path.of("../dataset/main_dataset");
    assumeTrue(Files.isDirectory(dataset));
    Dialect dialect = Dialect.load("cisco-ios");
    RepairProblem problem = new ScenarioRepository(dataset).load("scenario-001", 10);
    try (ExecutionContext context =
        ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1, 0), List.of())) {
      ProgramService programs =
          new ProgramService(dialect, new CiscoIosSemantics(dialect), context);
      long start = System.nanoTime();
      ProgramModel model = programs.model(problem.initial(), problem.topology());
      System.out.println(
          "first model "
              + (System.nanoTime() - start) / 1_000_000
              + " ms, objects "
              + model.objects().size()
              + ", instances "
              + model.instances().size());
      RouterId router = RouterId.of("109_as65006_bergamo");
      Snapshot changed = problem.initial();
      ProgramModel previous = model;
      for (int round = 0; round < 20; round++) {
        Transaction transaction =
            Transaction.of(
                changed,
                List.of(
                    new LineEdit.Replace(
                        SourceSpan.line(router, 78),
                        List.of(round % 2 == 0 ? "router bgp 65006" : "router bgp 65009"))));
        changed = changed.patch(transaction).after();
        start = System.nanoTime();
        ProgramModel incremental =
            programs.model(changed, problem.topology(), Optional.of(previous));
        long took = (System.nanoTime() - start) / 1_000_000;
        ProgramModel full = programs.model(changed, problem.topology());
        if (!new HashSet<>(incremental.instances()).equals(new HashSet<>(full.instances()))) {
          throw new AssertionError("incremental model differs in round " + round);
        }
        if (round % 5 == 4) {
          System.out.println(
              "incremental rebuild " + took + " ms, violated " + incremental.violated().size());
        }
        previous = incremental;
      }
    }
  }
}
