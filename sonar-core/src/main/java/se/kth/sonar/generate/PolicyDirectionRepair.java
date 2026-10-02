package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.grammar.Production;
import se.kth.sonar.grammar.Statement;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.text.SourceSpan;

/**
 * Puts a neighbor's route maps in the direction their clauses are written for.
 *
 * <p>A map that sets what only an export can use is misdirected when attached inbound, and one that
 * sets what only an import can use is misdirected when attached outbound. The map belongs in the
 * other direction, and its place belongs to a map written for it. When the other direction is free,
 * the misdirected attachment can simply be turned around. When the router also has a map written
 * for the direction that no session uses, that map is the one the attachment displaced: it takes
 * the direction, and the misdirected map moves to the other one if that is free. Exchanging two
 * misdirected attachments is the {@link PolicyAttachmentPermutation}.
 */
public final class PolicyDirectionRepair {
  private static final String PRODUCTION = "neighbor-route-map";

  private final SeedGenerator seeds;

  /** Creates the generator from the typed insertion stage. */
  public PolicyDirectionRepair(SeedGenerator seeds) {
    this.seeds = Objects.requireNonNull(seeds, "seeds");
  }

  private enum Direction {
    IN("in", "route-map-in", "import-attributes", "export-attributes"),
    OUT("out", "route-map-out", "export-attributes", "import-attributes");

    private final String keyword;
    private final String attribute;
    private final String own;
    private final String foreign;

    Direction(String keyword, String attribute, String own, String foreign) {
      this.keyword = keyword;
      this.attribute = attribute;
      this.own = own;
      this.foreign = foreign;
    }

    Direction other() {
      return this == IN ? OUT : IN;
    }
  }

  /** Returns the repairs of the misdirected attachments of the neighbor {@code site}. */
  public List<Hypothesis> generate(ProgramModel program, ProgramObject.Ref site) {
    ProgramObject neighbor =
        site.type().equals("bgp-neighbor") ? program.object(site).orElse(null) : null;
    if (neighbor == null) {
      return List.of();
    }
    Provenance provenance = new Provenance.Inferred("policy direction analysis");
    List<Hypothesis> result = new ArrayList<>();
    for (Direction direction : Direction.values()) {
      Optional<Statement> attachment = attachment(program, neighbor, direction);
      Optional<ProgramObject> attached = attached(program, neighbor, direction);
      if (attachment.isEmpty() || attached.isEmpty() || !attached.get().has(direction.foreign)) {
        continue;
      }
      boolean otherFree = attachment(program, neighbor, direction.other()).isEmpty();
      for (ProgramObject displaced : displaced(program, neighbor, direction)) {
        List<EditOperator> operators = new ArrayList<>();
        operators.add(
            new EditOperator.ReplaceSlots(
                attachment.get(), "map", displaced.ref().key(), provenance));
        if (otherFree) {
          operators.addAll(
              seeds.insert(
                  program,
                  neighbor.ref(),
                  PRODUCTION,
                  Map.of(
                      "peer", neighbor.ref().key(),
                      "map", attached.get().ref().key(),
                      "direction", List.of(direction.other().keyword)),
                  provenance));
        }
        result.add(Hypothesis.of(program.snapshot(), operators));
      }
      if (otherFree) {
        result.add(
            Hypothesis.of(
                program.snapshot(),
                List.of(
                    new EditOperator.ReplaceSlots(
                        attachment.get(),
                        "direction",
                        List.of(direction.other().keyword),
                        provenance))));
      }
    }
    return List.copyOf(result);
  }

  /**
   * Returns the maps of the neighbor's router that are written for {@code direction} and unused.
   */
  private static List<ProgramObject> displaced(
      ProgramModel program, ProgramObject neighbor, Direction direction) {
    return program.objectsOf(neighbor.ref().router()).stream()
        .filter(object -> object.ref().type().equals("route-map"))
        .filter(map -> map.has(direction.own) && !map.has(direction.foreign) && !map.has("used"))
        .sorted(Comparator.comparing(map -> map.ref().toString()))
        .toList();
  }

  private static Optional<ProgramObject> attached(
      ProgramModel program, ProgramObject neighbor, Direction direction) {
    return neighbor
        .value(direction.attribute)
        .map(
            value ->
                ProgramObject.Ref.isReference(value, "route-map")
                    ? ProgramObject.Ref.parse(value)
                    : new ProgramObject.Ref("route-map", neighbor.ref().router(), List.of(value)))
        .flatMap(program::object);
  }

  private static Optional<Statement> attachment(
      ProgramModel program, ProgramObject neighbor, Direction direction) {
    Set<SourceSpan> spans = new HashSet<>(neighbor.statements());
    return program.statements(neighbor.ref().router()).stream()
        .filter(statement -> spans.contains(statement.span()))
        .filter(
            statement -> {
              Production.Match match = statement.match().orElse(null);
              return match != null
                  && match.production().id().equals(PRODUCTION)
                  && match.values("direction").equals(List.of(direction.keyword));
            })
        .findFirst();
  }
}
