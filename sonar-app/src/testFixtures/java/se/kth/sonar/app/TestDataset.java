package se.kth.sonar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes small benchmark datasets for tests: three routers in a line, with specifications. */
public final class TestDataset {
  private static final String TOPOLOGY =
      """
      {"nodes": [{"id": "1", "hostname": "R1"}, {"id": "2", "hostname": "R2"}, {"id": "3", "hostname": "R3"}],
       "links": [
         {"nodes": ["1", "2"], "interfaces": [{"node": "1", "name": "GigabitEthernet0/0"}, {"node": "2", "name": "GigabitEthernet0/0"}]},
         {"nodes": ["2", "3"], "interfaces": [{"node": "2", "name": "GigabitEthernet0/1"}, {"node": "3", "name": "GigabitEthernet0/0"}]}]}
      """;

  private static final String SPECIFICATIONS =
      """
      predicate_type,node,prefix,waypoint,num_routes,sources
      reachability,r1,10.0.0.3/32,,,broken_removed
      reachability,r1,10.0.0.2/32,,,broken_removed
      reachability,r2,10.0.0.3/32,,,broken_removed
      reachability,r3,10.0.0.1/32,,,broken_removed
      isolation,r3,10.9.0.0/24,,,broken_added
      reachability,r2,10.0.0.1/32,,,intact
      reachability,r3,10.0.0.2/32,,,intact
      waypointing,r1,10.0.0.3/32,r2,,intact
      """;

  private TestDataset() {}

  /**
   * Writes {@code count} scenarios named {@code scenario-001} and onwards, and returns their names.
   */
  public static List<String> create(Path root, int count) {
    List<String> names = new ArrayList<>();
    for (int index = 1; index <= count; index++) {
      String name = "scenario-%03d".formatted(index);
      names.add(name);
      for (int router = 1; router <= 3; router++) {
        write(root.resolve(name + "/final_configs/configs/R" + router + ".cfg"), config(router));
      }
      write(root.resolve(name + "/data_and_metrics/topology.json"), TOPOLOGY);
      write(root.resolve(name + "/specifications/specifications_10pct.csv"), SPECIFICATIONS);
    }
    return names;
  }

  private static String config(int router) {
    return """
        hostname R%d
        !
        interface Loopback0
         ip address 10.0.0.%d 255.255.255.255
        !
        interface GigabitEthernet0/0
         ip address 10.1.%d.1 255.255.255.254
        !
        interface GigabitEthernet0/1
         ip address 10.2.%d.1 255.255.255.254
        !
        router bgp 6500%d
         bgp router-id 10.0.0.%d
        !
        """
        .formatted(router, router, router, router, router, router);
  }

  private static void write(Path file, String text) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
