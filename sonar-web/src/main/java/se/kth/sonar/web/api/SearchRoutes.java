package se.kth.sonar.web.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import se.kth.sonar.app.Json;
import se.kth.sonar.app.SearchJson;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.SearchSpace;
import se.kth.sonar.web.explore.SearchExplorer;
import se.kth.sonar.web.http.Call;
import se.kth.sonar.web.http.HttpError;
import se.kth.sonar.web.http.Router;

/**
 * Routes that build the search space of a scenario's faulty configurations, as the repair engine
 * would start from it, and browse it: the regions with their prior and each factor's weight, one
 * region's facts and candidates, and the generation of a region's candidates on request. A search
 * space is addressed by its scenario and specification file; {@code POST} starts it and {@code GET}
 * follows it. Region identifiers contain slashes, so they travel as query parameters and in bodies.
 */
public final class SearchRoutes {
  private static final int PAGE = 200;
  private static final int MAX_PAGE = 1000;
  private static final Set<String> KINDS = Set.of("site", "relation", "incidence", "transfer");

  private final ScenarioRepository repository;
  private final SearchExplorer explorer;
  private final Clock clock;

  private SearchRoutes(ScenarioRepository repository, SearchExplorer explorer, Clock clock) {
    this.repository = repository;
    this.explorer = explorer;
    this.clock = clock;
  }

  /**
   * A request to build a search space.
   *
   * @param percent the specification file, or null for the default one
   */
  record Explore(Integer percent) {}

  /**
   * A request to generate the candidates of a region.
   *
   * @param percent the specification file, or null for the default one
   * @param region the region identifier
   */
  record Expand(Integer percent, String region) {}

  /** Adds the routes to {@code router}. */
  public static void register(
      Router router, ScenarioRepository repository, SearchExplorer explorer, Clock clock) {
    SearchRoutes routes = new SearchRoutes(repository, explorer, clock);
    router.post(
        "/api/scenarios/{name}/search",
        call -> {
          String name = ScenarioRequests.scenario(call, repository);
          Explore request = call.body(Explore.class);
          int percent =
              ScenarioRequests.percent(
                  Optional.ofNullable(request.percent()).map(String::valueOf), name, repository);
          SearchExplorer.Requested requested =
              explorer.request(new SearchExplorer.Key(name, percent));
          call.json(
              requested.queued() ? 202 : 200,
              Map.of("search", routes.json(requested.exploration())));
        });
    router.get(
        "/api/scenarios/{name}/search",
        call -> call.json(200, Map.of("search", routes.json(routes.exploration(call)))));
    router.get(
        "/api/scenarios/{name}/search/regions",
        call -> call.json(200, routes.regions(routes.model(call), call)));
    router.get(
        "/api/scenarios/{name}/search/region",
        call -> {
          SearchExplorer.Model model = routes.model(call);
          Region.Id id = regionId(call.query("id"));
          call.json(200, Map.of("region", routes.region(model, id)));
        });
    router.post(
        "/api/scenarios/{name}/search/expand",
        call -> {
          String name = ScenarioRequests.scenario(call, repository);
          Expand request = call.body(Expand.class);
          int percent =
              ScenarioRequests.percent(
                  Optional.ofNullable(request.percent()).map(String::valueOf), name, repository);
          Region.Id id = regionId(Optional.ofNullable(request.region()));
          SearchExplorer.Requested requested;
          try {
            requested = explorer.expand(new SearchExplorer.Key(name, percent), id);
          } catch (IllegalStateException e) {
            throw HttpError.conflict(e.getMessage());
          } catch (NoSuchElementException e) {
            throw HttpError.notFound(e.getMessage());
          }
          SearchExplorer.Model model = requested.exploration().model().orElseThrow();
          call.json(requested.queued() ? 202 : 200, Map.of("region", routes.region(model, id)));
        });
  }

  private SearchExplorer.Exploration exploration(Call call) {
    String name = ScenarioRequests.scenario(call, repository);
    int percent = ScenarioRequests.percent(call.query("percent"), name, repository);
    return explorer
        .exploration(new SearchExplorer.Key(name, percent))
        .orElseThrow(() -> HttpError.notFound(name + " has no search space at " + percent + "%"));
  }

  private SearchExplorer.Model model(Call call) {
    SearchExplorer.Exploration exploration = exploration(call);
    return exploration
        .model()
        .orElseThrow(
            () ->
                HttpError.conflict(
                    "the search space of "
                        + exploration.key().scenario()
                        + " is "
                        + exploration.status().name().toLowerCase(Locale.ROOT)));
  }

  private static Region.Id regionId(Optional<String> value) {
    String id = value.map(String::strip).orElse("");
    if (id.isEmpty()) {
      throw HttpError.badRequest("the region is missing");
    }
    return new Region.Id(id);
  }

  private ObjectNode json(SearchExplorer.Exploration exploration) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("scenario", exploration.key().scenario());
    node.put("percent", exploration.key().percent());
    node.put("status", exploration.status().name().toLowerCase(Locale.ROOT));
    node.put("requested", exploration.requested().toEpochMilli());
    exploration.started().ifPresent(at -> node.put("started", at.toEpochMilli()));
    exploration.ended().ifPresent(at -> node.put("ended", at.toEpochMilli()));
    exploration
        .started()
        .ifPresent(
            started ->
                node.put(
                    "elapsedMillis",
                    exploration.ended().orElseGet(clock::instant).toEpochMilli()
                        - started.toEpochMilli()));
    exploration.error().ifPresent(error -> node.put("error", error));
    ArrayNode factors = node.putArray("factors");
    explorer.factors().forEach(factors::add);
    exploration.model().ifPresent(model -> summary(model, node.putObject("summary")));
    return node;
  }

  private static void summary(SearchExplorer.Model model, ObjectNode summary) {
    SearchSpace space = model.epoch().space();
    summary.put("routers", model.epoch().state().snapshot().routers().size());
    summary.put("specifications", model.epoch().state().problem().specifications().size());
    summary.put("unresolved", model.epoch().state().verification().verdict().unresolved().size());
    summary.put("regions", space.size());
    summary.put("partitions", space.partitions().size());
    ObjectNode kinds = summary.putObject("kinds");
    KINDS.forEach(kind -> kinds.put(kind, 0));
    long bound = 0;
    int expanded = 0;
    int candidates = 0;
    for (Region region : space.regions()) {
      String kind = SearchJson.kind(region);
      kinds.put(kind, kinds.path(kind).asInt() + 1);
      bound += region.bound();
      if (region.hypotheses() instanceof Region.Hypotheses.Admitted admitted) {
        expanded++;
        candidates += admitted.list().size();
      }
    }
    summary.put("bound", bound);
    summary.put("expanded", expanded);
    summary.put("dropped", model.dropped().size());
    summary.put(
        "expanding",
        (int)
            model.expansions().values().stream().filter(SearchExplorer.Expansion::active).count());
    summary.put("candidates", candidates);
  }

  private ObjectNode regions(SearchExplorer.Model model, Call call) {
    String kind = call.query("kind").orElse("");
    if (!kind.isEmpty() && !KINDS.contains(kind)) {
      throw HttpError.badRequest("unknown kind '" + kind + "'");
    }
    String router = call.query("router").orElse("");
    String partition = call.query("partition").orElse("");
    String query = call.query("query").orElse("").strip().toLowerCase(Locale.ROOT);
    int offset = ScenarioRequests.wholeNumber(call.query("offset"), "offset", 0);
    int limit =
        Math.min(MAX_PAGE, ScenarioRequests.wholeNumber(call.query("limit"), "limit", PAGE));
    SearchSpace space = model.epoch().space();
    List<Region> ranked = new ArrayList<>(space.regions());
    ranked.sort(
        Comparator.comparingDouble((Region region) -> -model.prior().probability(region.id())));
    Set<String> routers = new TreeSet<>();
    ObjectNode body = Json.mapper().createObjectNode();
    ArrayNode rows = body.putArray("regions");
    int matched = 0;
    for (Region region : ranked) {
      region.routers().forEach(name -> routers.add(name.name()));
      if ((!kind.isEmpty() && !SearchJson.kind(region).equals(kind))
          || (!router.isEmpty()
              && region.routers().stream().noneMatch(r -> r.name().equals(router)))
          || (!partition.isEmpty()
              && !space.partitionOf(region.id()).id().value().equals(partition))
          || (!query.isEmpty() && !region.id().value().toLowerCase(Locale.ROOT).contains(query))) {
        continue;
      }
      if (matched >= offset && rows.size() < limit) {
        rows.add(RegionJson.row(region, model));
      }
      matched++;
    }
    body.put("total", space.size());
    body.put("matched", matched);
    body.put("offset", offset);
    ArrayNode kinds = body.putArray("kinds");
    KINDS.stream().sorted().forEach(kinds::add);
    ArrayNode routerList = body.putArray("routers");
    routers.forEach(routerList::add);
    ArrayNode partitions = body.putArray("partitions");
    for (Partition member : space.partitions()) {
      ObjectNode entry = partitions.addObject();
      entry.put("id", member.id().value());
      entry.put("regions", member.regions().size());
      entry.put(
          "mass", member.regions().stream().mapToDouble(id -> model.prior().probability(id)).sum());
    }
    return body;
  }

  private ObjectNode region(SearchExplorer.Model model, Region.Id id) {
    Optional<Region> region = model.epoch().space().region(id);
    if (region.isPresent()) {
      return RegionJson.detail(region.get(), model, clock);
    }
    Region dropped = model.dropped().get(id);
    if (dropped == null) {
      throw HttpError.notFound("no region " + id.value());
    }
    return RegionJson.dropped(dropped, model, clock);
  }
}
