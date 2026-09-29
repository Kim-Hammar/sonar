package se.kth.sonar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The runs kept in one directory, one subdirectory per run, named by run identifier. */
public final class RunStore {
  private static final System.Logger LOG = System.getLogger(RunStore.class.getName());
  private static final Pattern ID = Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");
  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final Path directory;
  private final Clock clock;
  private final RandomGenerator random = RandomGenerator.getDefault();

  /** Creates a store in {@code directory}, which is created on the first run. */
  public RunStore(Path directory, Clock clock) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns whether {@code id} has the form of a run identifier. */
  public static boolean validId(String id) {
    return ID.matcher(id).matches();
  }

  /** Returns the store's directory. */
  public Path directory() {
    return directory;
  }

  /** Creates a queued run and returns its open log. */
  public RunLog create(
      String name, List<String> scenarios, RunSettings settings, boolean demo, String model)
      throws IOException {
    Files.createDirectories(directory);
    long now = clock.millis();
    for (int attempt = 0; attempt < 16; attempt++) {
      String id =
          STAMP.format(clock.instant())
              + "-"
              + HexFormat.of().toHexDigits((short) random.nextInt(1 << 16));
      Path run = directory.resolve(id);
      if (Files.exists(run)) {
        continue;
      }
      String display = name.isBlank() ? id : name.strip();
      RunDocument document =
          new RunDocument(
              id,
              display,
              demo,
              RunStatus.QUEUED.label(),
              now,
              null,
              null,
              null,
              scenarios,
              RunDocument.Settings.of(settings),
              model);
      return RunLog.create(run, document);
    }
    throw new IOException("cannot find a free run identifier in " + directory);
  }

  /**
   * Returns the documents of every stored run, oldest first. Runs that were queued or running when
   * the previous process stopped are marked interrupted; unreadable runs are skipped.
   */
  public List<RunDocument> documents() {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<RunDocument> documents = new ArrayList<>();
    try (Stream<Path> runs = Files.list(directory)) {
      for (Path run :
          runs.filter(path -> validId(path.getFileName().toString())).sorted().toList()) {
        readInterrupting(run).ifPresent(documents::add);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list the runs in " + directory, e);
    }
    documents.sort(Comparator.comparingLong(RunDocument::created));
    return List.copyOf(documents);
  }

  /** Opens the log of the stored run {@code id}. */
  public RunLog open(String id) throws IOException {
    if (!validId(id)) {
      throw new IllegalArgumentException("invalid run identifier '" + id + "'");
    }
    return RunLog.open(directory.resolve(id));
  }

  private Optional<RunDocument> readInterrupting(Path run) {
    try {
      RunDocument document = RunLog.readDocument(run);
      if (document.runStatus().terminal()) {
        return Optional.of(document);
      }
      try (RunLog log = RunLog.open(run)) {
        return Optional.of(
            log.update(
                current ->
                    current.withStatus(
                        RunStatus.INTERRUPTED, clock.millis(), "the server stopped")));
      }
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.WARNING, "skipping unreadable run " + run + ": " + e.getMessage());
      return Optional.empty();
    }
  }
}
