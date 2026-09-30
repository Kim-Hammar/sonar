package se.kth.sonar.web.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.app.Json;
import se.kth.sonar.app.SpecificationJson;
import se.kth.sonar.batfish.ForwardingEntry;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;
import se.kth.sonar.web.http.Call;
import se.kth.sonar.web.http.HttpError;
import se.kth.sonar.web.http.Router;
import se.kth.sonar.web.verify.ScenarioVerifier;

/**
 * Routes that verify a scenario's faulty configurations with the data-plane engine and browse what
 * it found: the state of every specification with its forwarding traces, the forwarding table, and
 * the routers that hold BGP routes to the specified destinations. A verification is addressed by
 * its scenario and specification file; {@code POST} starts one and {@code GET} follows it.
 */
public final class VerificationRoutes {
  private static final int PAGE = 200;
  private static final int MAX_PAGE = 1000;
  private static final String UNRESOLVED = "unresolved";
  private static final String FIXED = "fixed";
  private static final String SIDE_EFFECT = "side-effect";
  private static final String HOLDING = "holding";

  private final ScenarioRepository repository;
  private final ScenarioVerifier verifier;
  private final Clock clock;

  private VerificationRoutes(
      ScenarioRepository repository, ScenarioVerifier verifier, Clock clock) {
    this.repository = repository;
    this.verifier = verifier;
    this.clock = clock;
  }

  /**
   * A request to verify a scenario.
   *
   * @param percent the specification file, or null for the default one
   */
  record Verify(Integer percent) {}

  /** Adds the routes to {@code router}. */
  public static void register(
      Router router, ScenarioRepository repository, ScenarioVerifier verifier, Clock clock) {
    VerificationRoutes routes = new VerificationRoutes(repository, verifier, clock);
    router.post(
        "/api/scenarios/{name}/verification",
        call -> {
          String name = ScenarioRequests.scenario(call, repository);
          Verify request = call.body(Verify.class);
          int percent =
              ScenarioRequests.percent(
                  Optional.ofNullable(request.percent()).map(String::valueOf), name, repository);
          ScenarioVerifier.Requested requested =
              verifier.request(new ScenarioVerifier.Key(name, percent));
          call.json(
              requested.queued() ? 202 : 200, Map.of("verification", routes.json(requested.job())));
        });
    router.get(
        "/api/scenarios/{name}/verification",
        call -> call.json(200, Map.of("verification", routes.json(routes.job(call)))));
    router.get(
        "/api/scenarios/{name}/verification/specifications",
        call -> {
          ScenarioVerifier.Report report = routes.report(call);
          Map<String, String> states = states(report.verification().verdict());
          ObjectNode body = Json.mapper().createObjectNode();
          ArrayNode rows = body.putArray("specifications");
          for (Specification specification : report.specifications().all()) {
            ObjectNode row = SpecificationJson.row(specification);
            row.put("state", states.getOrDefault(specification.predicate().key(), HOLDING));
            rows.add(row);
          }
          call.json(200, body);
        });
    router.get(
        "/api/scenarios/{name}/verification/traces",
        call -> call.json(200, routes.traces(routes.report(call), call)));
    router.get(
        "/api/scenarios/{name}/verification/forwarding",
        call -> call.json(200, forwarding(routes.report(call), call)));
    router.get(
        "/api/scenarios/{name}/verification/routes",
        call -> call.json(200, routers(routes.report(call))));
  }

  private ScenarioVerifier.Job job(Call call) {
    String name = ScenarioRequests.scenario(call, repository);
    int percent = ScenarioRequests.percent(call.query("percent"), name, repository);
    return verifier
        .job(new ScenarioVerifier.Key(name, percent))
        .orElseThrow(() -> HttpError.notFound(name + " has not been verified at " + percent + "%"));
  }

  private ScenarioVerifier.Report report(Call call) {
    ScenarioVerifier.Job job = job(call);
    return job.report()
        .orElseThrow(
            () ->
                HttpError.conflict(
                    "the verification of "
                        + job.key().scenario()
                        + " is "
                        + job.status().name().toLowerCase(Locale.ROOT)));
  }

  private ObjectNode json(ScenarioVerifier.Job job) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("scenario", job.key().scenario());
    node.put("percent", job.key().percent());
    node.put("status", job.status().name().toLowerCase(Locale.ROOT));
    node.put("requested", job.requested().toEpochMilli());
    job.started().ifPresent(at -> node.put("started", at.toEpochMilli()));
    job.ended().ifPresent(at -> node.put("ended", at.toEpochMilli()));
    job.started()
        .ifPresent(
            started ->
                node.put(
                    "elapsedMillis",
                    job.ended().orElseGet(clock::instant).toEpochMilli() - started.toEpochMilli()));
    job.error().ifPresent(error -> node.put("error", error));
    job.report().ifPresent(report -> summary(report, node.putObject("summary")));
    return node;
  }

  private static void summary(ScenarioVerifier.Report report, ObjectNode summary) {
    Verdict verdict = report.verification().verdict();
    int total = report.specifications().size();
    int judged =
        verdict.unresolved().size() + verdict.fixed().size() + verdict.sideEffects().size();
    summary.put("routers", report.routers());
    summary.put("forwardingRows", report.forwarding().size());
    summary.put("specifications", total);
    summary.put(UNRESOLVED, verdict.unresolved().size());
    summary.put(FIXED, verdict.fixed().size());
    summary.put("sideEffects", verdict.sideEffects().size());
    summary.put(HOLDING, total - judged);
    summary.put("solved", verdict.solved());
  }

  private ObjectNode traces(ScenarioVerifier.Report report, Call call) {
    String key =
        call.query("specification")
            .orElseThrow(() -> HttpError.badRequest("the specification is missing"));
    Specification specification =
        report.specifications().all().stream()
            .filter(candidate -> candidate.predicate().key().equals(key))
            .findFirst()
            .orElseThrow(() -> HttpError.notFound("no specification " + key));
    DataPlaneEvidence evidence = report.verification().evidence();
    Prefix destination = specification.predicate().destination();
    ObjectNode body = SpecificationJson.row(specification);
    body.put("state", states(report.verification().verdict()).getOrDefault(key, HOLDING));
    ArrayNode flows = body.putArray("flows");
    for (DataPlaneEvidence.Trace trace :
        evidence.traces().getOrDefault(specification.predicate(), List.of())) {
      ObjectNode flow = flows.addObject();
      ArrayNode hops = flow.putArray("hops");
      trace.hops().forEach(hop -> hops.add(hop.name()));
      flow.put(
          "disposition", trace.disposition().name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
    names(body.putArray("routeHolders"), evidence.routeHolders().get(destination));
    names(body.putArray("forwarders"), evidence.forwarders().get(destination));
    return body;
  }

  private static ObjectNode forwarding(ScenarioVerifier.Report report, Call call) {
    String node = call.query("node").map(value -> value.toLowerCase(Locale.ROOT)).orElse("");
    String prefix = call.query("prefix").orElse("").strip();
    String action = call.query("action").orElse("");
    int offset = ScenarioRequests.wholeNumber(call.query("offset"), "offset", 0);
    int limit =
        Math.min(MAX_PAGE, ScenarioRequests.wholeNumber(call.query("limit"), "limit", PAGE));
    Set<String> nodes = new TreeSet<>();
    Set<String> actions = new TreeSet<>();
    ObjectNode body = Json.mapper().createObjectNode();
    ArrayNode rows = body.putArray("rows");
    int matched = 0;
    for (ForwardingEntry entry : report.forwarding()) {
      nodes.add(entry.node());
      actions.add(entry.action());
      if ((!node.isEmpty() && !entry.node().equals(node))
          || (!prefix.isEmpty() && !entry.prefix().contains(prefix))
          || (!action.isEmpty() && !entry.action().equals(action))) {
        continue;
      }
      if (matched >= offset && rows.size() < limit) {
        ObjectNode row = rows.addObject();
        row.put("node", entry.node());
        row.put("prefix", entry.prefix());
        entry.nextHopIp().ifPresent(ip -> row.put("nextHopIp", ip));
        entry.nextHopInterface().ifPresent(name -> row.put("nextHopInterface", name));
        row.put("action", entry.action());
        ArrayNode receivers = row.putArray("receivers");
        entry.receivers().stream().sorted().forEach(receivers::add);
      }
      matched++;
    }
    body.put("total", report.forwarding().size());
    body.put("matched", matched);
    body.put("offset", offset);
    ArrayNode nodeList = body.putArray("nodes");
    nodes.forEach(nodeList::add);
    ArrayNode actionList = body.putArray("actions");
    actions.forEach(actionList::add);
    return body;
  }

  private static ObjectNode routers(ScenarioVerifier.Report report) {
    DataPlaneEvidence evidence = report.verification().evidence();
    Set<Prefix> prefixes = new TreeSet<>(evidence.routeHolders().keySet());
    prefixes.addAll(evidence.forwarders().keySet());
    ObjectNode body = Json.mapper().createObjectNode();
    ArrayNode routes = body.putArray("routes");
    for (Prefix prefix : prefixes) {
      ObjectNode route = routes.addObject();
      route.put("prefix", prefix.toString());
      names(route.putArray("holders"), evidence.routeHolders().get(prefix));
      names(route.putArray("forwarders"), evidence.forwarders().get(prefix));
    }
    return body;
  }

  private static void names(ArrayNode array, Set<RouterId> routers) {
    if (routers != null) {
      routers.stream().map(RouterId::name).sorted().forEach(array::add);
    }
  }

  private static Map<String, String> states(Verdict verdict) {
    Map<String, String> states = new HashMap<>();
    verdict.unresolved().forEach(s -> states.put(s.predicate().key(), UNRESOLVED));
    verdict.fixed().forEach(s -> states.put(s.predicate().key(), FIXED));
    verdict.sideEffects().forEach(s -> states.put(s.predicate().key(), SIDE_EFFECT));
    return states;
  }
}
