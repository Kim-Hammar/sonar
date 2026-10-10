package se.kth.sonar.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.decision.DecisionOutcome;
import se.kth.sonar.decision.DecisionProblem;
import se.kth.sonar.decision.HypothesisRanking;
import se.kth.sonar.decision.RegionAssessment;
import se.kth.sonar.execution.Budget;
import se.kth.sonar.network.Topology;
import se.kth.sonar.report.RepairResult;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.trace.TraceEvent;
import se.kth.sonar.trace.TraceRecord;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Writes trace records as JSON: a small summary that carries the usage snapshot and the
 * specifications remaining, and a detail that the inspector loads on demand. It remembers each
 * scenario's current verdict, so a candidate's detail lists only the specifications it changed.
 */
final class TraceJson {
  private static final String UNRESOLVED = "unresolved";
  private static final String FIXED = "fixed";
  private static final String SIDE_EFFECT = "side-effect";

  private final JsonMapper mapper = Json.mapper();
  private final Map<String, Map<String, String>> states = new HashMap<>();

  /**
   * One encoded record.
   *
   * @param summary the summary line
   * @param detail the detail line
   */
  record Encoded(String summary, String detail) {}

  /** Encodes {@code record} as event {@code seq}, the {@code step}-th of its scenario. */
  Encoded encode(long seq, long step, TraceRecord record) {
    ObjectNode summary = mapper.createObjectNode();
    ObjectNode detail = mapper.createObjectNode();
    summary.put("seq", seq);
    summary.put("scenario", record.scenario());
    summary.put("step", step);
    summary.put("at", record.at().toEpochMilli());
    summary.put("type", type(record.event()));
    ObjectNode usage = summary.putObject("usage");
    for (Budget.Resource resource : Budget.Resource.values()) {
      usage.put(camel(resource.name()), record.usage().getOrDefault(resource, 0L));
    }
    detail.put("seq", seq);
    detail.put("type", type(record.event()));
    switch (record.event()) {
      case TraceEvent.Loaded loaded -> loaded(loaded, summary, detail);
      case TraceEvent.Baseline baseline -> {
        verdictCounts(baseline.verification().verdict(), summary);
        remember(record.scenario(), baseline.verification().verdict());
        detail.set("verdict", verdict(baseline.verification().verdict()));
        detail.set("evidence", evidence(baseline.verification().evidence(), null));
      }
      case TraceEvent.SpaceBuilt built -> space(built, summary, detail);
      case TraceEvent.Decided decided -> decided(decided, summary, detail);
      case TraceEvent.Tried tried -> tried(record.scenario(), tried, summary, detail);
      case TraceEvent.Rejected rejected -> rejected(rejected, summary, detail);
      case TraceEvent.Adopted adopted -> {
        summary.put("epoch", adopted.epoch());
        summary.put("adopted", adopted.hypotheses().size());
        ArrayNode changed = summary.putArray("routers");
        adopted.hypotheses().stream()
            .flatMap(hypothesis -> hypothesis.routers().stream())
            .distinct()
            .sorted()
            .forEach(router -> changed.add(router.name()));
        verdictCounts(adopted.verification().verdict(), summary);
        remember(record.scenario(), adopted.verification().verdict());
        detail.put("epoch", adopted.epoch());
        ArrayNode hypotheses = detail.putArray("hypotheses");
        adopted
            .hypotheses()
            .forEach(hypothesis -> hypotheses.add(SearchJson.hypothesis(hypothesis)));
        detail.set("verdict", verdict(adopted.verification().verdict()));
        detail.set("evidence", evidence(adopted.verification().evidence(), null));
      }
      case TraceEvent.Finished finished -> finished(finished.result(), summary, detail);
      case TraceEvent.Timings timings -> {
        ObjectNode phases = summary.putObject("nanos");
        timings
            .nanos()
            .forEach(
                (phase, durations) -> {
                  ArrayNode list = phases.putArray(phase);
                  durations.forEach(list::add);
                });
      }
      case TraceEvent.Failed failed -> {
        summary.put("reason", failed.reason());
        detail.put("reason", failed.reason());
      }
    }
    return new Encoded(write(summary), write(detail));
  }

  private static String type(TraceEvent event) {
    return switch (event) {
      case TraceEvent.Loaded _ -> "loaded";
      case TraceEvent.Baseline _ -> "baseline";
      case TraceEvent.SpaceBuilt _ -> "space-built";
      case TraceEvent.Decided _ -> "decided";
      case TraceEvent.Tried _ -> "tried";
      case TraceEvent.Rejected _ -> "rejected";
      case TraceEvent.Adopted _ -> "adopted";
      case TraceEvent.Finished _ -> "finished";
      case TraceEvent.Timings _ -> "timings";
      case TraceEvent.Failed _ -> "failed";
    };
  }

  private void loaded(TraceEvent.Loaded loaded, ObjectNode summary, ObjectNode detail) {
    Topology topology = loaded.problem().topology();
    List<Specification> specifications = loaded.problem().specifications().all();
    summary.put("routers", topology.routers().size());
    summary.put("links", topology.links().size());
    summary.put("specifications", specifications.size());
    summary.put("remaining", loaded.problem().specifications().broken().size());
    ObjectNode network = detail.putObject("topology");
    ArrayNode routers = network.putArray("routers");
    topology.routers().forEach(router -> routers.add(router.name()));
    ArrayNode links = network.putArray("links");
    for (Topology.Link link : topology.links()) {
      ObjectNode node = links.addObject();
      node.put("a", link.a().router().name());
      node.put("aInterface", link.a().interfaceName());
      node.put("b", link.b().router().name());
      node.put("bInterface", link.b().interfaceName());
    }
    ArrayNode rows = detail.putArray("specifications");
    specifications.forEach(specification -> rows.add(SpecificationJson.row(specification)));
  }

  private void space(TraceEvent.SpaceBuilt built, ObjectNode summary, ObjectNode detail) {
    SearchSpace space = built.space();
    summary.put("epoch", built.epoch());
    summary.put("regions", space.size());
    summary.put("partitions", space.partitions().size());
    detail.put("epoch", built.epoch());
    ArrayNode regions = detail.putArray("regions");
    for (Region region : space.regions()) {
      ObjectNode node = regions.addObject();
      node.put("id", region.id().value());
      node.put("kind", SearchJson.kind(region));
      ArrayNode routers = node.putArray("routers");
      region.routers().forEach(router -> routers.add(router.name()));
      node.put("targets", region.targets().size());
      node.put("bound", region.bound());
      node.put("partition", space.partitionOf(region.id()).id().value());
      built.prior().ifPresent(prior -> node.put("prior", prior.probability(region.id())));
    }
    ArrayNode partitions = detail.putArray("partitions");
    for (Partition partition : space.partitions()) {
      ObjectNode node = partitions.addObject();
      node.put("id", partition.id().value());
      ArrayNode members = node.putArray("regions");
      partition.regions().forEach(id -> members.add(id.value()));
    }
  }

  private void decided(TraceEvent.Decided decided, ObjectNode summary, ObjectNode detail) {
    DecisionProblem problem = decided.problem();
    DecisionOutcome<?> outcome = decided.outcome();
    String question = problem instanceof DecisionProblem.RegionChoice ? "region" : "hypothesis";
    String result = outcome(outcome);
    boolean cached = outcome.exchanges().stream().anyMatch(DecisionOutcome.Exchange::cached);
    summary.put("question", question);
    summary.put("outcome", result);
    summary.put("providerCalls", outcome.usage().providerCalls());
    summary.put("inputTokens", outcome.usage().inputTokens());
    summary.put("outputTokens", outcome.usage().outputTokens());
    summary.put("cached", cached);
    detail.put("question", question);
    detail.put("key", problem.key().hex());
    detail.put("outcome", result);
    ArrayNode context = detail.putArray("context");
    for (DecisionProblem.Context.Section section : problem.context().sections()) {
      ObjectNode node = context.addObject();
      node.put("title", section.title());
      node.put("body", section.body());
    }
    ArrayNode options = detail.putArray("options");
    problem
        .options()
        .forEach(
            (id, description) -> {
              ObjectNode option = options.addObject();
              option.put("id", id);
              option.put("description", description);
            });
    switch (outcome) {
      case DecisionOutcome.Answered<?> answered -> detail.set("answer", answer(answered.answer()));
      case DecisionOutcome.Unavailable<?> unavailable -> detail.put("reason", unavailable.reason());
      case DecisionOutcome.TooLarge<?> tooLarge ->
          detail.put(
              "reason",
              "the question has "
                  + tooLarge.size()
                  + " characters; the limit is "
                  + tooLarge.limit());
      case DecisionOutcome.Stopped<?> stopped ->
          detail.put("reason", "the budget of " + camel(stopped.resource().name()) + " ran out");
    }
    ArrayNode exchanges = detail.putArray("exchanges");
    for (DecisionOutcome.Exchange exchange : outcome.exchanges()) {
      ObjectNode node = exchanges.addObject();
      node.put("status", exchange.status());
      node.put("latencyMillis", exchange.latency().toMillis());
      node.put("cached", exchange.cached());
      node.put("request", exchange.request());
      node.put("reply", exchange.reply());
    }
  }

  private static String outcome(DecisionOutcome<?> outcome) {
    return switch (outcome) {
      case DecisionOutcome.Answered<?> _ -> "answered";
      case DecisionOutcome.Unavailable<?> _ -> "unavailable";
      case DecisionOutcome.TooLarge<?> _ -> "too-large";
      case DecisionOutcome.Stopped<?> _ -> "stopped";
    };
  }

  private ObjectNode answer(Object answer) {
    ObjectNode node = mapper.createObjectNode();
    if (answer instanceof RegionAssessment assessment) {
      node.put("none", assessment.none());
      ArrayNode regions = node.putArray("regions");
      for (Region.Id id : assessment.regions().ranked()) {
        ObjectNode region = regions.addObject();
        region.put("id", id.value());
        region.put("probability", assessment.regions().probability(id));
      }
    } else if (answer instanceof HypothesisRanking ranking) {
      ArrayNode order = node.putArray("order");
      for (ContentHash id : ranking.order()) {
        ObjectNode candidate = order.addObject();
        candidate.put("id", id.hex());
        candidate.put("probability", ranking.probabilities().probability(id));
      }
    } else {
      node.put("text", String.valueOf(answer));
    }
    return node;
  }

  private void tried(
      String scenario, TraceEvent.Tried tried, ObjectNode summary, ObjectNode detail) {
    Verdict verdict = tried.verification().verdict();
    summary.put("region", tried.region().value());
    summary.put("hypothesis", tried.hypothesis().id().abbreviated());
    summary.put("change", tried.change().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    verdictCounts(verdict, summary);
    detail.put("region", tried.region().value());
    detail.set("hypothesis", SearchJson.hypothesis(tried.hypothesis()));
    detail.put("change", summary.get("change").asText());
    Map<String, String> before = states.getOrDefault(scenario, Map.of());
    Map<String, String> after = statuses(verdict);
    Set<String> keys = new TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    Set<String> changed = new HashSet<>();
    ArrayNode changes = detail.putArray("changes");
    for (String key : keys) {
      String was = before.getOrDefault(key, "ok");
      String now = after.getOrDefault(key, "ok");
      if (!was.equals(now)) {
        changed.add(key);
        ObjectNode change = changes.addObject();
        change.put("key", key);
        change.put("before", was);
        change.put("after", now);
      }
    }
    detail.set("evidence", evidence(tried.verification().evidence(), changed));
  }

  private void rejected(TraceEvent.Rejected rejected, ObjectNode summary, ObjectNode detail) {
    summary.put("region", rejected.region().value());
    summary.put("hypothesis", rejected.hypothesis().id().abbreviated());
    summary.put("change", "verification-error");
    detail.put("region", rejected.region().value());
    detail.set("hypothesis", SearchJson.hypothesis(rejected.hypothesis()));
    detail.put("change", "verification-error");
    detail.put("reason", rejected.reason());
  }

  private void finished(RepairResult result, ObjectNode summary, ObjectNode detail) {
    String stop = stop(result.stop());
    summary.put("stop", stop);
    summary.put("adopted", result.adopted().size());
    verdictCounts(result.verdict(), summary);
    detail.put("stop", stop);
    if (result.stop() instanceof RepairResult.Stop.Failed failed) {
      detail.put("reason", failed.reason());
    }
    if (result.stop() instanceof RepairResult.Stop.BudgetExhausted exhausted) {
      detail.put("resource", camel(exhausted.resource().name()));
    }
    detail.set("verdict", verdict(result.verdict()));
    ArrayNode adopted = detail.putArray("adopted");
    result.adopted().forEach(transaction -> adopted.add(transaction.id().hex()));
  }

  private static String stop(RepairResult.Stop stop) {
    return switch (stop) {
      case RepairResult.Stop.Solved _ -> "solved";
      case RepairResult.Stop.BudgetExhausted _ -> "budget-exhausted";
      case RepairResult.Stop.TimeExpired _ -> "time-expired";
      case RepairResult.Stop.Cancelled _ -> "cancelled";
      case RepairResult.Stop.SearchExhausted _ -> "search-exhausted";
      case RepairResult.Stop.Abandoned _ -> "abandoned";
      case RepairResult.Stop.Failed _ -> "failed";
    };
  }

  private static void verdictCounts(Verdict verdict, ObjectNode summary) {
    summary.put("remaining", verdict.unresolved().size());
    summary.put("fixed", verdict.fixed().size());
    summary.put("sideEffects", verdict.sideEffects().size());
  }

  private void remember(String scenario, Verdict verdict) {
    states.put(scenario, statuses(verdict));
  }

  private static Map<String, String> statuses(Verdict verdict) {
    Map<String, String> statuses = new HashMap<>();
    verdict
        .unresolved()
        .forEach(specification -> statuses.put(specification.predicate().key(), UNRESOLVED));
    verdict.fixed().forEach(specification -> statuses.put(specification.predicate().key(), FIXED));
    verdict
        .sideEffects()
        .forEach(specification -> statuses.put(specification.predicate().key(), SIDE_EFFECT));
    return statuses;
  }

  private ObjectNode verdict(Verdict verdict) {
    ObjectNode node = mapper.createObjectNode();
    keys(node.putArray("unresolved"), verdict.unresolved());
    keys(node.putArray("fixed"), verdict.fixed());
    keys(node.putArray("sideEffects"), verdict.sideEffects());
    return node;
  }

  private static void keys(ArrayNode array, List<Specification> specifications) {
    specifications.forEach(specification -> array.add(specification.predicate().key()));
  }

  private ObjectNode evidence(DataPlaneEvidence evidence, Set<String> only) {
    ObjectNode node = mapper.createObjectNode();
    ArrayNode traces = node.putArray("traces");
    evidence.traces().entrySet().stream()
        .filter(entry -> only == null || only.contains(entry.getKey().key()))
        .sorted(Comparator.comparing(entry -> entry.getKey().key()))
        .forEach(
            entry -> {
              ObjectNode predicate = traces.addObject();
              predicate.put("predicate", entry.getKey().key());
              ArrayNode flows = predicate.putArray("flows");
              for (DataPlaneEvidence.Trace trace : entry.getValue()) {
                ObjectNode flow = flows.addObject();
                ArrayNode hops = flow.putArray("hops");
                trace.hops().forEach(hop -> hops.add(hop.name()));
                flow.put(
                    "disposition",
                    trace.disposition().name().toLowerCase(Locale.ROOT).replace('_', '-'));
              }
            });
    ArrayNode holders = node.putArray("routeHolders");
    evidence.routeHolders().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              ObjectNode holder = holders.addObject();
              holder.put("prefix", entry.getKey().toString());
              ArrayNode routers = holder.putArray("routers");
              entry.getValue().stream().sorted().forEach(router -> routers.add(router.name()));
            });
    return node;
  }

  private static String camel(String constant) {
    StringBuilder text = new StringBuilder();
    boolean upper = false;
    for (int index = 0; index < constant.length(); index++) {
      char c = constant.charAt(index);
      if (c == '_') {
        upper = true;
      } else {
        text.append(upper ? c : Character.toLowerCase(c));
        upper = false;
      }
    }
    return text.toString();
  }

  private String write(ObjectNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }
}
