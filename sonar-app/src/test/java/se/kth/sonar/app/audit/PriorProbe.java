package se.kth.sonar.app.audit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.app.Pipeline;
import se.kth.sonar.batfish.BatfishEngine;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.DecisionProblemFactory;
import se.kth.sonar.engine.Epoch;
import se.kth.sonar.engine.EpochBuilder;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.execution.ParallelismConfig;
import se.kth.sonar.execution.ResourcePool;
import se.kth.sonar.jev.JevRequestCodec;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.text.SourceSpan;
import se.kth.sonar.verify.DataPlaneEngine;
import se.kth.sonar.verify.Verification;
import se.kth.sonar.verify.VerificationService;

/**
 * Prints the prior over the regions of one scenario, factor by factor, optionally after applying
 * line replacements given as {@code sonar.probe.edit=router:line:text}, several separated by {@code
 * ;;}, or after replaying the first {@code sonar.probe.epoch} adoptions of the scenario recorded in
 * the run directory {@code sonar.probe.run}, except the adoption numbered {@code sonar.probe.skip}.
 * Line replacements given as {@code sonar.probe.then} are applied after the replay, to the lines as
 * they are then, and the relation instances they make violated are printed. Regions whose
 * identifier contains {@code sonar.audit.focus} are printed with their rank wherever they are.
 */
@Tag("audit")
class PriorProbe {
  private static final Path DATASET =
      Path.of(System.getProperty("sonar.dataset", "../dataset/main_dataset"));
  private static final int SHOWN = Integer.getInteger("sonar.probe.shown", 25);
  private static final int PAYLOAD_LIMIT = 60_000;

  @Test
  void printThePrior(@TempDir Path storage) throws IOException {
    assumeTrue(Files.isDirectory(DATASET), "dataset absent");
    String name = System.getProperty("sonar.audit.scenarios", "scenario-001");
    String edit = System.getProperty("sonar.probe.edit", "");
    Dialect dialect = Dialect.load("cisco-ios");
    ScenarioRepository scenarios = new ScenarioRepository(DATASET);
    try (ExecutionContext context =
            ExecutionContext.open(new ParallelismConfig(1, 1, 8, 1), List.of());
        ResourcePool<DataPlaneEngine> engines =
            ResourcePool.of(
                1,
                () -> new BatfishEngine(new BatfishEngine.Settings(storage.resolve("bf"))),
                DataPlaneEngine::close)) {
      EpochBuilder epochs = Pipeline.epochs(dialect, context);
      VerificationService verifier = new VerificationService(engines, context);
      RepairProblem problem =
          scenarios.load(name, Integer.getInteger("sonar.probe.specifications", 100));
      Snapshot snapshot = problem.initial();
      if (!edit.isEmpty()) {
        snapshot = snapshot.patch(Transaction.of(snapshot, replacements(edit))).after();
      }
      String run = System.getProperty("sonar.probe.run", "");
      if (!run.isEmpty()) {
        snapshot = replay(snapshot, Path.of(run), name, Integer.getInteger("sonar.probe.epoch", 0));
      }
      String then = System.getProperty("sonar.probe.then", "");
      if (!then.isEmpty()) {
        ProgramModel before = epochs.programs().model(snapshot, problem.topology());
        snapshot = snapshot.patch(Transaction.of(snapshot, replacements(then))).after();
        Set<ContentHash> satisfied = new HashSet<>();
        before.instances().stream()
            .filter(RelationInstance::satisfied)
            .forEach(instance -> satisfied.add(instance.id()));
        for (RelationInstance instance :
            epochs.programs().model(snapshot, problem.topology(), Optional.of(before)).violated()) {
          if (satisfied.contains(instance.id())) {
            System.out.println(
                "breaks "
                    + instance.relation()
                    + " "
                    + instance.roles()
                    + " "
                    + instance.violated().stream().map(Requirement::text).toList());
          }
        }
      }
      Verification verification = verifier.verify(snapshot, problem.specifications());
      Epoch epoch =
          epochs.initial(
              new RepairProblem(name, snapshot, problem.topology(), problem.specifications()),
              verification);
      RepairState state = epoch.state();
      System.out.println(
          "unresolved "
              + verification.verdict().unresolved().size()
              + ", side effects "
              + verification.verdict().sideEffects().size());
      SearchSpace space = epoch.space();
      Distribution<Region.Id> prior = epochs.prior(epoch);
      List<Region> ranked = new ArrayList<>(space.regions());
      ranked.sort(Comparator.comparingDouble((Region r) -> -prior.probability(r.id())));
      String focus = System.getProperty("sonar.audit.focus", "");
      for (int rank = 0; rank < ranked.size(); rank++) {
        Region region = ranked.get(rank);
        if (rank >= SHOWN && (focus.isEmpty() || !region.id().value().contains(focus))) {
          continue;
        }
        StringBuilder line = new StringBuilder();
        line.append(String.format("%4d  %.5f", rank, prior.probability(region.id())))
            .append("  ")
            .append(region.id())
            .append("  targets ")
            .append(region.targets().size());
        epochs
            .prior()
            .breakdown(region, state)
            .forEach(
                (label, weight) ->
                    line.append("  ")
                        .append(label)
                        .append(' ')
                        .append(String.format("%.3f", weight)));
        System.out.println(line);
      }
      Partition partition =
          Partition.comparison(
              ranked.subList(0, Math.min(16, ranked.size())).stream().map(Region::id).toList());
      Epoch expanded = epoch;
      for (Region.Id id : partition.regions()) {
        expanded = epochs.expand(expanded, id);
      }
      DecisionProblem.RegionChoice question =
          new DecisionProblemFactory(PAYLOAD_LIMIT)
              .regionChoice(state, expanded.space(), partition, List.of());
      System.out.println("question of " + question.size() + " characters");
      for (DecisionProblem.Context.Section section : question.context().sections()) {
        System.out.println("## " + section.title());
        System.out.println(section.body());
      }
      System.out.println("## Options");
      question.options().forEach((id, text) -> System.out.println(id + ": " + text));
      System.out.println(
          "request " + new JevRequestCodec("jev").encode(question).body().length() + " characters");
    }
  }

  /** Reads line replacements written as {@code router:line:text}, separated by {@code ;;}. */
  private static List<LineEdit> replacements(String text) {
    List<LineEdit> edits = new ArrayList<>();
    for (String each : text.split(";;", -1)) {
      String[] parts = each.strip().split(":", 3);
      int line = Integer.parseInt(parts[1]);
      edits.add(
          new LineEdit.Replace(
              new SourceSpan(RouterId.of(parts[0]), line, line), List.of(parts[2])));
    }
    return edits;
  }

  /**
   * Returns {@code snapshot} after the first {@code epochs} adoptions of {@code name} recorded in
   * the run at {@code run}. An adoption that replaced an earlier one is not replayed faithfully.
   */
  private static Snapshot replay(Snapshot snapshot, Path run, String name, int epochs)
      throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    Set<Long> adoptions = new HashSet<>();
    for (String line : Files.readAllLines(run.resolve("events.jsonl"))) {
      JsonNode event = mapper.readTree(line);
      if (event.path("scenario").asText().equals(name)
          && event.path("type").asText().equals("adopted")
          && event.path("epoch").asInt() <= epochs
          && event.path("epoch").asInt() != Integer.getInteger("sonar.probe.skip", -1)) {
        adoptions.add(event.path("seq").asLong());
      }
    }
    for (String line : Files.readAllLines(run.resolve("details.jsonl"))) {
      if (!line.contains("\"type\":\"adopted\"")) {
        continue;
      }
      JsonNode detail = mapper.readTree(line);
      if (!adoptions.contains(detail.path("seq").asLong())) {
        continue;
      }
      Snapshot adopted = snapshot;
      for (JsonNode hypothesis : detail.path("hypotheses")) {
        Transaction transaction = Transaction.of(snapshot, edits(hypothesis.path("edits")));
        if (!adopted.equals(snapshot)) {
          transaction = transaction.rebaseOver(delta(snapshot, adopted)).orElseThrow();
        }
        adopted = adopted.patch(transaction).after();
      }
      snapshot = adopted;
    }
    return snapshot;
  }

  private static List<LineEdit> edits(JsonNode nodes) {
    List<LineEdit> edits = new ArrayList<>();
    for (JsonNode node : nodes) {
      RouterId router = RouterId.of(node.path("router").asText());
      List<String> lines = new ArrayList<>();
      node.path("lines").forEach(line -> lines.add(line.asText()));
      edits.add(
          switch (node.path("op").asText()) {
            case "delete" -> new LineEdit.Delete(span(router, node));
            case "replace" -> new LineEdit.Replace(span(router, node), lines);
            case "insert" -> new LineEdit.InsertAfter(router, node.path("after").asInt(), lines);
            default -> new LineEdit.Append(router, lines);
          });
    }
    return edits;
  }

  private static SourceSpan span(RouterId router, JsonNode node) {
    return new SourceSpan(router, node.path("first").asInt(), node.path("last").asInt());
  }

  private static Snapshot.Delta delta(Snapshot before, Snapshot after) {
    TreeSet<RouterId> changed = new TreeSet<>();
    for (RouterId router : after.routers()) {
      if (!before.text(router).digest().equals(after.text(router).digest())) {
        changed.add(router);
      }
    }
    return new Snapshot.Delta(before, after, changed);
  }
}
