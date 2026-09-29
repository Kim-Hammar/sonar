package se.kth.sonar.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Topology;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.text.RouterId;

/**
 * Loads benchmark scenarios from a dataset directory with one directory per scenario, holding
 * {@code final_configs/configs/*.cfg}, {@code data_and_metrics/topology.json} and {@code
 * specifications/specifications_<percent>pct.csv}. It reads only what a repair may see, never the
 * ground truth. Scenario names are checked against the listing, so no name can reach a path outside
 * the dataset.
 */
public final class ScenarioRepository {
  private static final Pattern SPECIFICATION_FILE =
      Pattern.compile("specifications_(\\d{1,3})pct\\.csv");
  private static final String CONFIGURATIONS = "final_configs/configs";
  private static final String TOPOLOGY = "data_and_metrics/topology.json";
  private static final String SPECIFICATIONS = "specifications";
  private static final String CONFIG_SUFFIX = ".cfg";

  private final Path root;
  private final JsonMapper mapper = JsonMapper.builder().build();

  /** Creates a repository over the dataset directory {@code root}. */
  public ScenarioRepository(Path root) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
  }

  /** Returns the dataset directory. */
  public Path root() {
    return root;
  }

  /** Returns the names of the directories that hold a scenario, in name order. */
  public List<String> scenarios() {
    try (Stream<Path> entries = Files.list(root)) {
      return entries
          .filter(entry -> Files.isDirectory(entry.resolve(CONFIGURATIONS)))
          .map(entry -> entry.getFileName().toString())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list the dataset " + root, e);
    }
  }

  /** Returns whether {@code scenario} names a scenario of the dataset. */
  public boolean contains(String scenario) {
    return scenarios().contains(scenario);
  }

  /** Returns the percentages for which {@code scenario} has a specification file, ascending. */
  public List<Integer> specificationPercents(String scenario) {
    Path directory = directory(scenario).resolve(SPECIFICATIONS);
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .map(file -> SPECIFICATION_FILE.matcher(file.getFileName().toString()))
          .filter(Matcher::matches)
          .map(matcher -> Integer.parseInt(matcher.group(1)))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list the specifications of " + scenario, e);
    }
  }

  /** Returns the faulty configurations of {@code scenario}. */
  public Snapshot configurations(String scenario) {
    Map<String, String> texts = new TreeMap<>();
    try (Stream<Path> files = Files.list(directory(scenario).resolve(CONFIGURATIONS))) {
      for (Path file :
          files.filter(path -> path.getFileName().toString().endsWith(CONFIG_SUFFIX)).toList()) {
        texts.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the configurations of " + scenario, e);
    }
    return Snapshot.of(texts);
  }

  /**
   * Returns the physical topology of {@code scenario}. Links that join a router to itself connect
   * no two routers and are left out.
   */
  public Topology topology(String scenario) {
    JsonNode document;
    try (Reader reader =
        Files.newBufferedReader(directory(scenario).resolve(TOPOLOGY), StandardCharsets.UTF_8)) {
      document = mapper.readTree(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the topology of " + scenario, e);
    }
    Map<String, RouterId> routers = new HashMap<>();
    for (JsonNode node : document.path("nodes")) {
      routers.put(text(node, "id", scenario), RouterId.of(text(node, "hostname", scenario)));
    }
    List<Topology.Link> links = new ArrayList<>();
    for (JsonNode link : document.path("links")) {
      JsonNode ends = link.path("nodes");
      String a = ends.path(0).asText();
      String b = ends.path(1).asText();
      if (a.equals(b)) {
        continue;
      }
      Map<String, String> interfaces = new HashMap<>();
      for (JsonNode end : link.path("interfaces")) {
        interfaces.put(text(end, "node", scenario), text(end, "name", scenario));
      }
      links.add(
          new Topology.Link(
              endpoint(routers, interfaces, a, scenario),
              endpoint(routers, interfaces, b, scenario)));
    }
    return Topology.of(routers.values(), links);
  }

  /** Returns the specifications of {@code scenario} that cover {@code percent} per cent. */
  public SpecificationSet specifications(String scenario, int percent) {
    if (!specificationPercents(scenario).contains(percent)) {
      throw new IllegalArgumentException(scenario + " has no specifications for " + percent + "%");
    }
    Path file =
        directory(scenario)
            .resolve(SPECIFICATIONS)
            .resolve("specifications_" + percent + "pct.csv");
    try {
      return SpecificationCsv.load(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the specifications of " + scenario, e);
    }
  }

  /** Loads {@code scenario} with the specifications that cover {@code percent} per cent. */
  public RepairProblem load(String scenario, int percent) {
    return new RepairProblem(
        scenario, configurations(scenario), topology(scenario), specifications(scenario, percent));
  }

  private Path directory(String scenario) {
    if (!contains(scenario)) {
      throw new IllegalArgumentException("unknown scenario '" + scenario + "'");
    }
    return root.resolve(scenario);
  }

  private static Topology.Endpoint endpoint(
      Map<String, RouterId> routers, Map<String, String> interfaces, String node, String scenario) {
    RouterId router = routers.get(node);
    String name = interfaces.get(node);
    if (router == null || name == null) {
      throw new IllegalStateException(
          "the topology of " + scenario + " has a link to unknown node " + node);
    }
    return new Topology.Endpoint(router, name);
  }

  private static String text(JsonNode node, String field, String scenario) {
    JsonNode value = node.get(field);
    if (value == null || !value.isValueNode()) {
      throw new IllegalStateException("the topology of " + scenario + " lacks '" + field + "'");
    }
    return value.asText();
  }
}
