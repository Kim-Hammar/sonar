package se.kth.sonar.web.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import se.kth.sonar.app.Json;
import se.kth.sonar.app.SpecificationJson;
import se.kth.sonar.dataset.ScenarioRepository;
import se.kth.sonar.grammar.Grammar;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.web.http.Call;
import se.kth.sonar.web.http.HttpError;
import se.kth.sonar.web.http.Router;

/**
 * Routes that browse the dataset: scenarios, topologies, specifications and configurations. Each
 * router's BGP AS is read by matching its root-level lines against the dialect's grammar.
 */
public final class ScenarioRoutes {
  private final ScenarioRepository repository;
  private final Grammar grammar;
  private final Map<String, ObjectNode> topologies = new ConcurrentHashMap<>();

  private ScenarioRoutes(ScenarioRepository repository, Grammar grammar) {
    this.repository = repository;
    this.grammar = grammar;
  }

  /** Adds the routes to {@code router}. */
  public static void register(Router router, ScenarioRepository repository, Grammar grammar) {
    ScenarioRoutes routes = new ScenarioRoutes(repository, grammar);
    router.get(
        "/api/scenarios", call -> call.json(200, Map.of("scenarios", repository.scenarios())));
    router.get(
        "/api/scenarios/{name}",
        call -> {
          String name = routes.scenario(call);
          ObjectNode topology = routes.topology(name);
          call.json(
              200,
              Map.of(
                  "name", name,
                  "routers", topology.path("routers").size(),
                  "links", topology.path("links").size(),
                  "specificationPercents", repository.specificationPercents(name)));
        });
    router.get(
        "/api/scenarios/{name}/topology",
        call -> call.json(200, routes.topology(routes.scenario(call))));
    router.get(
        "/api/scenarios/{name}/specifications",
        call -> {
          String name = routes.scenario(call);
          int percent = routes.percent(call, name);
          SpecificationSet specifications = repository.specifications(name, percent);
          ObjectNode body = Json.mapper().createObjectNode();
          body.put("percent", percent);
          ArrayNode rows = body.putArray("specifications");
          specifications
              .all()
              .forEach(specification -> rows.add(SpecificationJson.row(specification)));
          call.json(200, body);
        });
    router.get(
        "/api/scenarios/{name}/routers/{router}/config",
        call -> {
          Snapshot configurations = repository.configurations(routes.scenario(call));
          RouterId id = RouterId.of(call.parameter("router"));
          if (!configurations.contains(id)) {
            throw HttpError.notFound("no router " + call.parameter("router"));
          }
          call.send(
              200,
              "text/plain; charset=utf-8",
              configurations.text(id).text().getBytes(StandardCharsets.UTF_8),
              "no-cache");
        });
  }

  private String scenario(Call call) {
    String name = call.parameter("name");
    if (!repository.contains(name)) {
      throw HttpError.notFound("no scenario " + name);
    }
    return name;
  }

  private int percent(Call call, String name) {
    List<Integer> available = repository.specificationPercents(name);
    Optional<String> requested = call.query("percent");
    if (requested.isEmpty()) {
      if (available.isEmpty()) {
        throw HttpError.notFound(name + " has no specifications");
      }
      return available.contains(10) ? 10 : available.getFirst();
    }
    try {
      int percent = Integer.parseInt(requested.get());
      if (!available.contains(percent)) {
        throw HttpError.notFound(name + " has no specifications for " + percent + "%");
      }
      return percent;
    } catch (NumberFormatException e) {
      throw HttpError.badRequest("percent must be a whole number");
    }
  }

  private ObjectNode topology(String name) {
    return topologies.computeIfAbsent(name, this::buildTopology);
  }

  private ObjectNode buildTopology(String name) {
    Topology topology = repository.topology(name);
    Snapshot configurations = repository.configurations(name);
    ObjectNode body = Json.mapper().createObjectNode();
    ArrayNode routers = body.putArray("routers");
    for (RouterId router : topology.routers()) {
      ObjectNode node = routers.addObject();
      node.put("name", router.name());
      if (configurations.contains(router)) {
        asn(configurations.text(router)).ifPresent(asn -> node.put("asn", asn));
      }
    }
    ArrayNode links = body.putArray("links");
    for (Topology.Link link : topology.links()) {
      ObjectNode node = links.addObject();
      node.put("a", link.a().router().name());
      node.put("aInterface", link.a().interfaceName());
      node.put("b", link.b().router().name());
      node.put("bInterface", link.b().interfaceName());
    }
    return body;
  }

  private Optional<Long> asn(ConfigText text) {
    for (String line : text.lines()) {
      if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
        continue;
      }
      for (Production.Match match : grammar.match(grammar.root(), Grammar.tokens(line))) {
        if (match.production().id().equals("router-bgp")) {
          return Optional.of(Long.parseLong(match.value("asn")));
        }
      }
    }
    return Optional.empty();
  }
}
