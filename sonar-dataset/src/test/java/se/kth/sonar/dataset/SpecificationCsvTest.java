package se.kth.sonar.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.text.RouterId;

class SpecificationCsvTest {
  private static final String FILE =
      """
      predicate_type,node,prefix,waypoint,num_routes,sources
      reachability,0_as65003_0,10.180.0.1/32,,,intact
      reachability,0_as65003_0,10.180.0.26/32,,,broken_removed
      reachability,0_as65003_0,10.180.0.1/32,,,broken_added
      waypointing,0_as65002_linz,10.180.0.10/32,10_as65002_vienna,,intact
      load_balancing,0_as65001_tampa,10.180.0.66/32,,2,broken_removed|intact
      load_balancing,0_as65001_tampa,10.180.0.67/32,,,intact
      isolation,0_as65002_linz,10.181.0.0/31,,,intact
      teleportation,0_as65002_linz,10.181.0.0/31,,,intact
      """;

  @Test
  void readsEveryKnownPredicateType() throws IOException {
    List<Specification> rows = SpecificationCsv.read(new StringReader(FILE));
    assertThat(rows).hasSize(6);
    assertThat(rows.get(3).predicate())
        .isEqualTo(
            new ForwardingPredicate.Waypointing(
                RouterId.of("0_as65002_linz"),
                Prefix.parse("10.180.0.10/32"),
                RouterId.of("10_as65002_vienna")));
    assertThat(rows.get(4).predicate())
        .isEqualTo(
            new ForwardingPredicate.LoadBalancing(
                RouterId.of("0_as65001_tampa"), Prefix.parse("10.180.0.66/32"), 2));
    assertThat(rows.get(4).status().initial()).isEqualTo(Specification.Condition.BROKEN);
    assertThat(rows.get(5).predicate()).isInstanceOf(ForwardingPredicate.Isolation.class);
  }

  @Test
  void loadingResolvesDuplicatePredicates(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("specifications_100pct.csv");
    Files.writeString(file, FILE, StandardCharsets.UTF_8);
    SpecificationSet set = SpecificationCsv.load(file);
    assertThat(set.size()).isEqualTo(5);
    assertThat(set.broken()).hasSize(3);
    assertThat(
            set.find(
                    new ForwardingPredicate.Reachability(
                        RouterId.of("0_as65003_0"), Prefix.parse("10.180.0.1/32")))
                .orElseThrow()
                .status()
                .label())
        .isEqualTo("broken_added");
  }

  @Test
  void rejectsMalformedFiles() {
    assertThatThrownBy(() -> SpecificationCsv.read(new StringReader("")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SpecificationCsv.read(new StringReader("predicate_type,node\n")))
        .hasMessageContaining("no column prefix");
    assertThatThrownBy(
            () ->
                SpecificationCsv.read(
                    new StringReader(
                        "predicate_type,node,prefix,waypoint,num_routes,sources\nreachability,r1\n")))
        .hasMessageContaining("line 2");
    assertThatThrownBy(
            () ->
                SpecificationCsv.read(
                    new StringReader(
                        "predicate_type,node,prefix,waypoint,num_routes,sources\nreachability,r1,10.0.0.1/24,,,intact\n")))
        .hasMessageContaining("line 2");
  }
}
