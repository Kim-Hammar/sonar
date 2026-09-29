package se.kth.sonar.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.kth.sonar.network.Topology;
import se.kth.sonar.state.RepairProblem;
import se.kth.sonar.text.RouterId;

class ScenarioRepositoryTest {
  private static final String TOPOLOGY =
      """
      {"topology": "Tiny", "node_count": 2,
       "nodes": [{"id": "0", "hostname": "R1", "config_paths": {}}, {"id": "1", "hostname": "R2", "config_paths": {}}],
       "links": [
         {"id": "edge-0", "nodes": ["0", "1"], "interfaces": [{"node": "0", "name": "GigabitEthernet0/0"}, {"node": "1", "name": "GigabitEthernet0/1"}]},
         {"id": "edge-1", "nodes": ["1", "1"], "interfaces": [{"node": "1", "name": "GigabitEthernet0/2"}, {"node": "1", "name": "GigabitEthernet0/2"}]}]}
      """;
  private static final String SPECIFICATIONS =
      """
      predicate_type,node,prefix,waypoint,num_routes,sources
      reachability,r1,10.0.0.0/24,,,broken_removed
      reachability,r2,10.0.1.0/24,,,intact
      """;

  @TempDir Path root;
  private ScenarioRepository repository;

  private void write(String relative, String text) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  @BeforeEach
  void dataset() throws IOException {
    write("scenario-001/final_configs/configs/R1.cfg", "hostname R1\n");
    write("scenario-001/final_configs/configs/R2.cfg", "hostname R2\n");
    write("scenario-001/data_and_metrics/topology.json", TOPOLOGY);
    write("scenario-001/specifications/specifications_10pct.csv", SPECIFICATIONS);
    write("scenario-001/specifications/specifications_100pct.csv", SPECIFICATIONS);
    write("scenario-002/final_configs/configs/R9.cfg", "hostname R9\n");
    write("notes/readme.txt", "not a scenario");
    write("README.md", "dataset");
    repository = new ScenarioRepository(root);
  }

  @Test
  void listsScenariosAndTheirSpecificationFiles() {
    assertThat(repository.scenarios()).containsExactly("scenario-001", "scenario-002");
    assertThat(repository.specificationPercents("scenario-001")).containsExactly(10, 100);
    assertThat(repository.specificationPercents("scenario-002")).isEmpty();
  }

  @Test
  void loadsConfigurationsTopologyAndSpecifications() {
    RepairProblem problem = repository.load("scenario-001", 10);
    RouterId r1 = RouterId.of("r1");
    RouterId r2 = RouterId.of("r2");
    assertThat(problem.name()).isEqualTo("scenario-001");
    assertThat(problem.initial().routers()).containsExactly(r1, r2);
    assertThat(problem.initial().text(r1).text()).isEqualTo("hostname R1\n");
    assertThat(problem.topology().links()).hasSize(1);
    assertThat(problem.topology().peer(new Topology.Endpoint(r1, "GigabitEthernet0/0")))
        .contains(new Topology.Endpoint(r2, "GigabitEthernet0/1"));
    assertThat(problem.specifications().size()).isEqualTo(2);
    assertThat(problem.specifications().broken()).hasSize(1);
  }

  @Test
  void rejectsNamesOutsideTheListing() {
    assertThatThrownBy(() -> repository.load("../scenario-001", 10))
        .hasMessageContaining("unknown scenario");
    assertThatThrownBy(() -> repository.configurations("notes"))
        .hasMessageContaining("unknown scenario");
    assertThatThrownBy(() -> repository.specifications("scenario-001", 50))
        .hasMessageContaining("no specifications");
    assertThat(repository.contains("scenario-002")).isTrue();
  }
}
