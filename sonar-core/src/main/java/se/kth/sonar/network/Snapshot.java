package se.kth.sonar.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;

/**
 * An immutable set of router configurations, identified by content. Patching shares every untouched
 * configuration with the base snapshot, so a candidate costs memory only for the routers it
 * changes.
 */
public final class Snapshot {
  private final NavigableMap<RouterId, File> files;
  private final ContentHash id;

  private Snapshot(NavigableMap<RouterId, File> files) {
    this.files = Collections.unmodifiableNavigableMap(files);
    ContentHash.Builder digest = ContentHash.builder();
    files.forEach(
        (router, file) -> digest.add(router.name()).add(file.name()).add(file.text().digest()));
    this.id = digest.build();
  }

  /**
   * Creates a snapshot from configuration texts keyed by file name, such as {@code R1.cfg}.
   *
   * @throws IllegalArgumentException if two file names denote the same router
   */
  public static Snapshot of(Map<String, String> textsByFileName) {
    NavigableMap<RouterId, File> files = new TreeMap<>();
    textsByFileName.forEach(
        (name, text) -> {
          RouterId router = RouterId.fromFileName(name);
          if (files.put(router, new File(name, ConfigText.of(text))) != null) {
            throw new IllegalArgumentException("two files denote router " + router);
          }
        });
    return new Snapshot(files);
  }

  /** Returns the routers in name order. */
  public NavigableSet<RouterId> routers() {
    return files.navigableKeySet();
  }

  /** Returns the configuration of {@code router}. */
  public ConfigText text(RouterId router) {
    return file(router).text();
  }

  /** Returns the file name of the configuration of {@code router}. */
  public String fileName(RouterId router) {
    return file(router).name();
  }

  /** Returns whether the snapshot has a configuration for {@code router}. */
  public boolean contains(RouterId router) {
    return files.containsKey(router);
  }

  /** Returns the content identifier, which covers every router, file name, and text. */
  public ContentHash id() {
    return id;
  }

  /**
   * Applies {@code transaction} and returns the change.
   *
   * @throws IllegalStateException if the transaction was built against other versions of the
   *     routers it touches
   */
  public Delta patch(Transaction transaction) {
    Map<RouterId, List<LineEdit>> byRouter = new LinkedHashMap<>();
    for (LineEdit edit : transaction.edits()) {
      byRouter.computeIfAbsent(edit.router(), router -> new ArrayList<>()).add(edit);
    }
    NavigableMap<RouterId, File> patched = new TreeMap<>(files);
    NavigableSet<RouterId> changed = new TreeSet<>();
    byRouter.forEach(
        (router, edits) -> {
          File file = file(router);
          ContentHash expected = transaction.base().get(router);
          if (!file.text().digest().equals(expected)) {
            throw new IllegalStateException(
                "transaction "
                    + transaction.id().abbreviated()
                    + " was built for another version of "
                    + router);
          }
          ConfigText after = file.text().apply(edits);
          if (!after.digest().equals(file.text().digest())) {
            patched.put(router, new File(file.name(), after));
            changed.add(router);
          }
        });
    Snapshot after = changed.isEmpty() ? this : new Snapshot(patched);
    return new Delta(this, after, Collections.unmodifiableNavigableSet(changed));
  }

  private File file(RouterId router) {
    File file = files.get(router);
    if (file == null) {
      throw new NoSuchElementException("no configuration for router " + router);
    }
    return file;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Snapshot that && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Snapshot[" + files.size() + " routers, " + id.abbreviated() + "]";
  }

  private record File(String name, ConfigText text) {}

  /**
   * The effect of applying a transaction.
   *
   * @param before the snapshot the transaction was applied to
   * @param after the resulting snapshot
   * @param changed the routers whose text changed
   */
  public record Delta(Snapshot before, Snapshot after, NavigableSet<RouterId> changed) {
    /** Checks the components. */
    public Delta {
      Objects.requireNonNull(before, "before");
      Objects.requireNonNull(after, "after");
      Objects.requireNonNull(changed, "changed");
    }

    /** Returns whether the transaction left every configuration unchanged. */
    public boolean changesNothing() {
      return changed.isEmpty();
    }
  }
}
