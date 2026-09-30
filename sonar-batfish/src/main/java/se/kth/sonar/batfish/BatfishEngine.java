package se.kth.sonar.batfish;

import com.google.common.cache.CacheBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.batfish.common.BatfishLogger;
import org.batfish.common.NetworkSnapshot;
import org.batfish.datamodel.Bgpv4Route;
import org.batfish.datamodel.ConcreteInterfaceAddress;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.dataplane.ibdp.IncrementalDataPlanePlugin;
import org.batfish.identifiers.NetworkId;
import org.batfish.identifiers.SnapshotId;
import org.batfish.identifiers.StorageBasedIdResolver;
import org.batfish.main.Batfish;
import org.batfish.storage.FileBasedStorage;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.spec.SpecificationSet;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEngine;

/**
 * Computes data planes with Batfish running inside this process, with no server and no container.
 *
 * <p>An analysis writes the snapshot's configurations to the engine's private storage, parses and
 * converts them, computes the data plane, and mines the specified predicates from the forwarding
 * behaviour of every route, as the benchmark's Batfish build does; the snapshot's storage is then
 * deleted. An engine holds one Batfish instance and serves one thread at a time. Engines in one
 * process use distinct network and snapshot names, because Batfish keeps some state per name.
 */
public final class BatfishEngine implements DataPlaneEngine {
  private static final AtomicLong ENGINES = new AtomicLong();

  private final Settings settings;
  private final BatfishLogger logger = new BatfishLogger(BatfishLogger.LEVELSTR_WARN, false);
  private final String networkName = "sonar-" + ENGINES.incrementAndGet();
  private final NetworkId network = new NetworkId(networkName);
  private FileBasedStorage storage;
  private Batfish batfish;
  private long analyses;

  /**
   * Settings of one engine.
   *
   * @param storage a directory for Batfish's storage, used by this engine only
   */
  public record Settings(Path storage) {
    /** Checks the storage directory. */
    public Settings {
      Objects.requireNonNull(storage, "storage");
    }
  }

  /** Creates an engine; Batfish starts on the first analysis. */
  public BatfishEngine(Settings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public Observation analyze(Snapshot snapshot, SpecificationSet specifications) {
    return analyze(snapshot, specifications, rows -> {});
  }

  /** Analyses {@code snapshot} and also hands the forwarding rows to {@code inspector}. */
  Observation analyze(
      Snapshot snapshot, SpecificationSet specifications, Consumer<List<ForwardingRow>> inspector) {
    Batfish instance = instance();
    analyses++;
    SnapshotId id = new SnapshotId(networkName + "-s" + analyses);
    NetworkSnapshot target = new NetworkSnapshot(network, id);
    instance.getSettings().setTestrig(id.getId());
    instance.getSettings().setSnapshotName(id.getId());
    try {
      for (RouterId router : snapshot.routers()) {
        byte[] text = snapshot.text(router).text().getBytes(StandardCharsets.UTF_8);
        storage.storeSnapshotInputObject(
            new ByteArrayInputStream(text), "configs/" + snapshot.fileName(router), target);
      }
      SortedMap<String, Configuration> configurations = instance.loadConfigurations(target);
      instance.computeDataPlane(target);
      DataPlane dataPlane = instance.loadDataPlane(target);
      List<ForwardingRow> rows =
          ForwardingTable.rows(
              dataPlane, configurations, instance.getTopologyProvider().getLayer3Topology(target));
      inspector.accept(rows);
      return PredicateMiner.observe(
          rows, owners(configurations), holders(dataPlane), specifications);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write snapshot " + id + " to Batfish storage", e);
    } finally {
      deleteSnapshot(id);
    }
  }

  private Batfish instance() {
    if (batfish != null) {
      return batfish;
    }
    org.batfish.config.Settings configured = new org.batfish.config.Settings(new String[0]);
    configured.setLogger(logger);
    configured.setStorageBase(settings.storage());
    configured.setContainer(networkName);
    configured.setTestrig("none");
    configured.setSnapshotName("none");
    try {
      Files.createDirectories(settings.storage());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot create Batfish storage " + settings.storage(), e);
    }
    storage = new FileBasedStorage(settings.storage(), logger);
    storage.initNetwork(network);
    batfish =
        new Batfish(
            configured,
            CacheBuilder.newBuilder().maximumSize(1).build(),
            CacheBuilder.newBuilder().maximumSize(1).build(),
            new HashMap<>(),
            CacheBuilder.newBuilder().maximumSize(1).build(),
            storage,
            new StorageBasedIdResolver(storage));
    IncrementalDataPlanePlugin plugin = new IncrementalDataPlanePlugin();
    plugin.initialize(batfish);
    batfish.registerDataPlanePlugin(plugin, IncrementalDataPlanePlugin.PLUGIN_NAME);
    return batfish;
  }

  /** Returns the routers owning each interface address, active or not. */
  private static Map<Ip, Set<String>> owners(Map<String, Configuration> configurations) {
    Map<Ip, Set<String>> owners = new HashMap<>();
    for (Configuration configuration : configurations.values()) {
      for (Interface anInterface : configuration.getAllInterfaces().values()) {
        for (ConcreteInterfaceAddress address : anInterface.getAllConcreteAddresses()) {
          owners
              .computeIfAbsent(address.getIp(), ip -> new HashSet<>())
              .add(configuration.getHostname());
        }
      }
    }
    return owners;
  }

  /** Returns the routers whose best BGP routes cover each prefix. */
  private static Map<Prefix, Set<RouterId>> holders(DataPlane dataPlane) {
    Map<Prefix, Set<RouterId>> holders = new HashMap<>();
    dataPlane
        .getBgpRoutes()
        .cellSet()
        .forEach(
            cell -> {
              for (Bgpv4Route route : cell.getValue()) {
                holders
                    .computeIfAbsent(
                        Prefix.parse(route.getNetwork().toString()), p -> new HashSet<>())
                    .add(RouterId.of(cell.getRowKey()));
              }
            });
    return holders;
  }

  /** Deletes every directory of the storage that is named after {@code id}. */
  private void deleteSnapshot(SnapshotId id) {
    try (Stream<Path> paths = Files.walk(settings.storage(), 4)) {
      paths
          .filter(
              path -> Files.isDirectory(path) && path.getFileName().toString().equals(id.getId()))
          .toList()
          .forEach(BatfishEngine::delete);
    } catch (IOException ignored) {
      settings.storage().toFile().deleteOnExit();
    }
  }

  private static void delete(Path directory) {
    if (directory == null || !Files.isDirectory(directory)) {
      return;
    }
    try (Stream<Path> files = Files.walk(directory)) {
      files
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  path.toFile().deleteOnExit();
                }
              });
    } catch (IOException ignored) {
      directory.toFile().deleteOnExit();
    }
  }

  @Override
  public void close() {
    delete(settings.storage());
  }
}
