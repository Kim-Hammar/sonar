package se.kth.sonar.dataset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.text.RouterId;

/**
 * Reads the benchmark's specification files: CSV with the columns {@code predicate_type}, {@code
 * node}, {@code prefix}, {@code waypoint}, {@code num_routes} and {@code sources}, where {@code
 * sources} holds the status label.
 */
public final class SpecificationCsv {
  private static final Pattern COMMA = Pattern.compile(",");
  private static final List<String> COLUMNS =
      List.of("predicate_type", "node", "prefix", "waypoint", "num_routes", "sources");

  private SpecificationCsv() {}

  /**
   * Reads the rows of a specification file in order. As in the benchmark's evaluator, rows of
   * unknown predicate types and load-balancing rows without a route count are skipped.
   *
   * @throws IllegalArgumentException if the header lacks a column or a row is malformed
   */
  public static List<Specification> read(Reader source) throws IOException {
    BufferedReader reader = new BufferedReader(source);
    String header = reader.readLine();
    if (header == null) {
      throw new IllegalArgumentException("the specification file is empty");
    }
    Map<String, Integer> index = new HashMap<>();
    String[] names = COMMA.split(header.strip(), -1);
    for (int column = 0; column < names.length; column++) {
      index.put(names[column].strip(), column);
    }
    for (String column : COLUMNS) {
      if (!index.containsKey(column)) {
        throw new IllegalArgumentException("the specification file has no column " + column);
      }
    }
    List<Specification> rows = new ArrayList<>();
    int number = 1;
    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      number++;
      if (line.isBlank()) {
        continue;
      }
      String[] cells = COMMA.split(line, -1);
      if (cells.length != names.length) {
        throw new IllegalArgumentException(
            "line " + number + " has " + cells.length + " columns, expected " + names.length);
      }
      try {
        row(cells, index).ifPresent(rows::add);
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("line " + number + ": " + invalid.getMessage(), invalid);
      }
    }
    return List.copyOf(rows);
  }

  /** Reads the specification file at {@code file} and resolves duplicate rows. */
  public static SpecificationSet load(Path file) throws IOException {
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return SpecificationSet.of(read(reader));
    }
  }

  private static Optional<Specification> row(String[] cells, Map<String, Integer> index) {
    String type = cell(cells, index, "predicate_type");
    RouterId source = RouterId.of(cell(cells, index, "node"));
    Prefix destination = Prefix.parse(cell(cells, index, "prefix"));
    Optional<ForwardingPredicate> predicate =
        switch (type) {
          case "reachability" ->
              Optional.of(new ForwardingPredicate.Reachability(source, destination));
          case "isolation" -> Optional.of(new ForwardingPredicate.Isolation(source, destination));
          case "waypointing" ->
              Optional.of(
                  new ForwardingPredicate.Waypointing(
                      source, destination, RouterId.of(cell(cells, index, "waypoint"))));
          case "load_balancing" -> {
            String routes = cell(cells, index, "num_routes");
            yield routes.isEmpty()
                ? Optional.empty()
                : Optional.of(
                    new ForwardingPredicate.LoadBalancing(
                        source, destination, Integer.parseInt(routes)));
          }
          default -> Optional.empty();
        };
    Specification.Status status = Specification.Status.parse(cell(cells, index, "sources"));
    return predicate.map(value -> new Specification(value, status));
  }

  private static String cell(String[] cells, Map<String, Integer> index, String column) {
    return cells[index.get(column)].strip();
  }
}
