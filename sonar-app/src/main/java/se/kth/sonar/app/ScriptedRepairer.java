package se.kth.sonar.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.CancellationException;
import java.util.random.RandomGenerator;
import se.kth.sonar.common.Distribution;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.engine.Repairer;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.execution.RunScope;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.Dialect;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceSink;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.verify.Verification;

/**
 * A deterministic stand-in for the repair engine, for demonstrations and tests. It takes the same
 * steps as the engine and emits the same trace events, drawing every choice from the run scope's
 * random generator: it builds a search space over the routers of violated specifications, asks a
 * simulated decision model which region to try, verifies a candidate that adds an interface
 * description, and adopts the candidate when the simulated verdict improves. The verdicts are
 * invented and exist only to exercise the interfaces that display a run.
 */
public final class ScriptedRepairer implements Repairer {
  private static final int MAX_STEPS = 200;
  private static final int MAX_REGIONS = 8;
  private static final int MAX_TRACES = 20;
  private static final int SHOWN_SPECIFICATIONS = 40;
  private static final String MODEL = "scripted";

  private final Duration pace;
  private final Production description;

  /** Creates a repairer that pauses for {@code pace} between steps. */
  public ScriptedRepairer(Duration pace) {
    this.pace = Objects.requireNonNull(pace, "pace");
    this.description = Dialect.load("cisco-ios").grammar().production("description").orElseThrow();
  }

  @Override
  public RepairResult repair(RepairProblem problem) {
    RunScope scope =
        RunScope.current()
            .orElseThrow(
                () -> new IllegalStateException("the scripted repairer needs a run scope"));
    RandomGenerator random = scope.random("scripted/" + problem.name());
    Budget budget = scope.budget();
    Snapshot snapshot = problem.initial();
    Verdict current = new Verdict(problem.specifications().broken(), List.of(), List.of());
    budget.charge(Budget.Resource.VERIFICATIONS, 1);
    TraceSink.emit(
        new TraceEvent.Baseline(
            new Verification(snapshot.id(), current, evidence(problem, current, random))));
    List<Transaction> adopted = new ArrayList<>();
    int epoch = 0;
    for (int step = 1; ; step++) {
      Optional<RepairResult.Stop> stop = stop(scope, current, step);
      if (stop.isPresent()) {
        return new RepairResult(
            problem.name(), snapshot, current, adopted, stop.get(), budget.usage());
      }
      pause();
      SearchSpace space = space(snapshot, current, random);
      TraceSink.emit(new TraceEvent.SpaceBuilt(epoch, space));
      Region region = decide(space, current, random, budget);
      pause();
      Hypothesis candidate = candidate(snapshot, region, step, random);
      Verdict after = outcome(problem, current, random);
      Snapshot result = snapshot.patch(candidate.transaction()).after();
      budget.charge(Budget.Resource.VERIFICATIONS, 1);
      Verification verification =
          new Verification(result.id(), after, evidence(problem, after, random));
      TraceSink.emit(
          new TraceEvent.Tried(region.id(), candidate, verification, current.changeTo(after)));
      if (after.improvesOn(current)) {
        epoch++;
        snapshot = result;
        adopted.add(candidate.transaction());
        current = after;
        TraceSink.emit(new TraceEvent.Adopted(epoch, List.of(candidate), verification));
      }
    }
  }

  private static Optional<RepairResult.Stop> stop(RunScope scope, Verdict current, int step) {
    if (current.solved()) {
      return Optional.of(new RepairResult.Stop.Solved());
    }
    if (scope.cancellation().isCancelled()) {
      return Optional.of(new RepairResult.Stop.Cancelled());
    }
    for (Budget.Resource resource : Budget.Resource.values()) {
      if (scope.budget().exhausted(resource)) {
        return Optional.of(new RepairResult.Stop.BudgetExhausted(resource));
      }
    }
    if (scope.budget().expired()) {
      return Optional.of(new RepairResult.Stop.TimeExpired());
    }
    return step > MAX_STEPS
        ? Optional.of(new RepairResult.Stop.SearchExhausted())
        : Optional.empty();
  }

  private static SearchSpace space(Snapshot snapshot, Verdict current, RandomGenerator random) {
    List<RouterId> routers =
        current.unresolved().stream()
            .map(specification -> specification.predicate().source())
            .distinct()
            .filter(snapshot::contains)
            .limit(MAX_REGIONS)
            .toList();
    if (routers.isEmpty()) {
      routers = snapshot.routers().stream().limit(MAX_REGIONS).toList();
    }
    List<Region> regions = new ArrayList<>();
    for (RouterId router : routers) {
      regions.add(
          new SiteRegion(
              new Region.Id("site:" + router.name()),
              new ProgramObject.Ref("router", router, List.of()),
              current.unresolved(),
              Map.of(router, snapshot.text(router).digest()),
              new Region.Hypotheses.Deferred(1 + random.nextInt(8))));
    }
    return SearchSpace.partitioned(regions, new SearchSpace.Capacity(16, 64));
  }

  private static Region decide(
      SearchSpace space, Verdict current, RandomGenerator random, Budget budget) {
    Partition partition = space.partitions().getFirst();
    List<Region> regions =
        partition.regions().stream().map(id -> space.region(id).orElseThrow()).toList();
    StringBuilder listing = new StringBuilder();
    for (Region region : regions) {
      listing
          .append(region.id().value())
          .append(": ")
          .append(region.bound())
          .append(" candidates\n");
    }
    DecisionProblem.Context context =
        new DecisionProblem.Context(
            List.of(
                new DecisionProblem.Context.Section(
                    "Task",
                    "Choose the region most likely to hold a repair of the violated specifications."),
                new DecisionProblem.Context.Section(
                    "Violated specifications",
                    String.join(
                        "\n",
                        current.unresolved().stream()
                            .limit(SHOWN_SPECIFICATIONS)
                            .map(specification -> specification.predicate().key())
                            .toList())),
                new DecisionProblem.Context.Section("Regions", listing.toString().strip())));
    SequencedMap<String, String> options = new LinkedHashMap<>();
    regions.forEach(
        region ->
            options.put(
                region.id().value(),
                "edit " + region.routers().first().name() + ", " + region.bound() + " candidates"));
    DecisionProblem.RegionChoice question =
        new DecisionProblem.RegionChoice(partition, regions, options, context);
    Map<Region.Id, Double> weights = new LinkedHashMap<>();
    regions.forEach(region -> weights.put(region.id(), 0.05 + random.nextDouble()));
    RegionAssessment assessment =
        new RegionAssessment(Distribution.of(weights), 0.05 + 0.2 * random.nextDouble());
    long inputTokens = context.size() / 4 + 600L + random.nextInt(400);
    long outputTokens = 60L + random.nextInt(140);
    Duration latency = Duration.ofMillis(400L + random.nextInt(2200));
    budget.charge(Budget.Resource.DECISIONS, 1);
    budget.charge(Budget.Resource.PROVIDER_CALLS, 1);
    budget.charge(Budget.Resource.INPUT_TOKENS, inputTokens);
    budget.charge(Budget.Resource.OUTPUT_TOKENS, outputTokens);
    DecisionOutcome.Exchange exchange =
        new DecisionOutcome.Exchange(
            request(question), reply(assessment, inputTokens, outputTokens), 200, latency, false);
    TraceSink.emit(
        new TraceEvent.Decided(
            question,
            new DecisionOutcome.Answered<>(
                assessment,
                new DecisionOutcome.Usage(1, inputTokens, outputTokens),
                List.of(exchange))));
    return space.region(assessment.regions().argmax()).orElseThrow();
  }

  private Hypothesis candidate(Snapshot snapshot, Region region, int step, RandomGenerator random) {
    RouterId router = region.routers().first();
    ConfigText text = snapshot.text(router);
    List<Integer> interfaces = new ArrayList<>();
    for (int line = 1; line <= text.lineCount(); line++) {
      if (text.line(line).startsWith("interface ")) {
        interfaces.add(line);
      }
    }
    int after =
        interfaces.isEmpty() ? text.lineCount() : interfaces.get(random.nextInt(interfaces.size()));
    EditOperator operator =
        new EditOperator.InsertStatement(
            router,
            after,
            " ",
            description,
            Map.of("text", List.of("sonar", "demo", "candidate", Integer.toString(step))),
            new Provenance.Existing());
    return Hypothesis.of(snapshot, List.of(operator));
  }

  private static Verdict outcome(RepairProblem problem, Verdict current, RandomGenerator random) {
    List<Specification> unresolved = new ArrayList<>(current.unresolved());
    List<Specification> fixed = new ArrayList<>(current.fixed());
    List<Specification> sideEffects = new ArrayList<>();
    double roll = random.nextDouble();
    if (roll < 0.55) {
      int count = 1 + random.nextInt(Math.min(3, unresolved.size()));
      for (int index = 0; index < count; index++) {
        fixed.add(unresolved.remove(random.nextInt(unresolved.size())));
      }
    } else if (roll >= 0.85) {
      List<Specification> intact =
          problem.specifications().all().stream()
              .filter(
                  specification ->
                      specification.status().initial() == Specification.Condition.INTACT)
              .toList();
      if (!intact.isEmpty()) {
        sideEffects.add(intact.get(random.nextInt(intact.size())));
      }
    }
    return new Verdict(unresolved, fixed, sideEffects);
  }

  private static DataPlaneEvidence evidence(
      RepairProblem problem, Verdict verdict, RandomGenerator random) {
    Map<ForwardingPredicate, List<DataPlaneEvidence.Trace>> traces = new LinkedHashMap<>();
    for (Specification specification : verdict.unresolved().stream().limit(MAX_TRACES).toList()) {
      List<RouterId> hops =
          walk(
              problem.topology(),
              specification.predicate().source(),
              1 + random.nextInt(4),
              random);
      DataPlaneEvidence.Disposition disposition =
          random.nextBoolean()
              ? DataPlaneEvidence.Disposition.NO_ROUTE
              : DataPlaneEvidence.Disposition.DENIED;
      traces.put(
          specification.predicate(), List.of(new DataPlaneEvidence.Trace(hops, disposition)));
    }
    return new DataPlaneEvidence(traces, Map.of(), Map.of());
  }

  private static List<RouterId> walk(
      Topology topology, RouterId start, int length, RandomGenerator random) {
    List<RouterId> hops = new ArrayList<>(List.of(start));
    RouterId at = start;
    for (int hop = 0; hop < length; hop++) {
      List<RouterId> next =
          topology.neighbours(at).stream().filter(router -> !hops.contains(router)).toList();
      if (next.isEmpty()) {
        break;
      }
      at = next.get(random.nextInt(next.size()));
      hops.add(at);
    }
    return hops;
  }

  private static String request(DecisionProblem.RegionChoice question) {
    ObjectNode request = Json.mapper().createObjectNode();
    request.put("model", MODEL);
    ObjectNode state = request.putObject("state");
    question.context().sections().forEach(section -> state.put(section.title(), section.body()));
    ObjectNode decision = request.putObject("questions").putObject("decision");
    decision.put("type", "choice");
    decision.put("instructions", "Which region most likely holds a repair?");
    ObjectNode criteria = decision.putObject("criteria");
    question
        .regions()
        .forEach(region -> criteria.put(region.id().value(), "routers " + region.routers()));
    return write(request);
  }

  private static String reply(RegionAssessment assessment, long inputTokens, long outputTokens) {
    ObjectNode reply = Json.mapper().createObjectNode();
    reply.put("model", MODEL);
    ObjectNode decision = reply.putObject("answers").putObject("decision");
    decision.put("type", "choice");
    decision.put("choice", assessment.regions().argmax().value());
    ObjectNode probabilities = decision.putObject("probabilities");
    assessment
        .regions()
        .asMap()
        .forEach((id, probability) -> probabilities.put(id.value(), probability));
    ObjectNode usage = reply.putObject("usage");
    usage.put("input_tokens", inputTokens);
    usage.put("output_tokens", outputTokens);
    return write(reply);
  }

  private static String write(ObjectNode node) {
    try {
      return Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void pause() {
    if (pace.isZero()) {
      return;
    }
    try {
      Thread.sleep(pace);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("the scripted repair was interrupted");
    }
  }
}
