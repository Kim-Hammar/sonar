package se.kth.sonar.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.LineDiff;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.RouterId;

/**
 * Line edits built against specific versions of the routers they touch. Two transactions with the
 * same edits against the same versions have the same identifier.
 */
public final class Transaction {
  private final List<LineEdit> edits;
  private final NavigableMap<RouterId, ContentHash> base;
  private final ContentHash id;

  private Transaction(List<LineEdit> edits, NavigableMap<RouterId, ContentHash> base) {
    this.edits = edits;
    this.base = base;
    ContentHash.Builder digest = ContentHash.builder();
    base.forEach((router, version) -> digest.add(router.name()).add(version));
    for (LineEdit edit : edits) {
      encode(digest, edit);
    }
    this.id = digest.build();
  }

  /**
   * Creates a transaction from {@code edits} against the versions of the touched routers in {@code
   * base}.
   *
   * @throws IllegalArgumentException if there is no edit or an edit names an unknown router
   */
  public static Transaction of(Snapshot base, List<? extends LineEdit> edits) {
    if (edits.isEmpty()) {
      throw new IllegalArgumentException("a transaction needs at least one edit");
    }
    NavigableMap<RouterId, ContentHash> versions = new TreeMap<>();
    for (LineEdit edit : edits) {
      if (!base.contains(edit.router())) {
        throw new IllegalArgumentException("edit names unknown router " + edit.router());
      }
      versions.put(edit.router(), base.text(edit.router()).digest());
    }
    return new Transaction(List.copyOf(edits), Collections.unmodifiableNavigableMap(versions));
  }

  /** Returns the content identifier. */
  public ContentHash id() {
    return id;
  }

  /** Returns the edits in order. */
  public List<LineEdit> edits() {
    return edits;
  }

  /** Returns the routers the transaction touches. */
  public NavigableSet<RouterId> routers() {
    return base.navigableKeySet();
  }

  /** Returns the digest of the configuration each touched router had when the edits were built. */
  public NavigableMap<RouterId, ContentHash> base() {
    return base;
  }

  /** Returns the number of lines removed plus the number of lines added. */
  public int cost() {
    return edits.stream().mapToInt(LineEdit::cost).sum();
  }

  /**
   * Re-expresses this transaction against the result of {@code adopted}: on every router the
   * adoption changed, both changes are applied to the common base together and the result is diffed
   * against the adopted text. Returns nothing when the two change the same lines.
   *
   * @throws IllegalStateException if this transaction was not built against {@code adopted}'s base
   */
  public Optional<Transaction> rebaseOver(Snapshot.Delta adopted) {
    List<LineEdit> rebased = new ArrayList<>();
    Map<RouterId, List<LineEdit>> byRouter = new LinkedHashMap<>();
    for (LineEdit edit : edits) {
      byRouter.computeIfAbsent(edit.router(), router -> new ArrayList<>()).add(edit);
    }
    for (Map.Entry<RouterId, List<LineEdit>> entry : byRouter.entrySet()) {
      RouterId router = entry.getKey();
      ConfigText before = adopted.before().text(router);
      if (!before.digest().equals(base.get(router))) {
        throw new IllegalStateException(
            "transaction " + id.abbreviated() + " was built for another version of " + router);
      }
      if (!adopted.changed().contains(router)) {
        rebased.addAll(entry.getValue());
        continue;
      }
      ConfigText after = adopted.after().text(router);
      List<LineEdit> together = new ArrayList<>(LineDiff.between(router, before, after));
      together.addAll(entry.getValue());
      ConfigText merged;
      try {
        merged = before.apply(together);
      } catch (IllegalArgumentException conflict) {
        return Optional.empty();
      }
      rebased.addAll(LineDiff.between(router, after, merged));
    }
    return rebased.isEmpty() ? Optional.empty() : Optional.of(of(adopted.after(), rebased));
  }

  private static void encode(ContentHash.Builder digest, LineEdit edit) {
    switch (edit) {
      case LineEdit.Delete delete ->
          digest
              .add("delete")
              .add(delete.router().name())
              .add(delete.span().first())
              .add(delete.span().last());
      case LineEdit.Replace replace -> {
        digest
            .add("replace")
            .add(replace.router().name())
            .add(replace.span().first())
            .add(replace.span().last());
        lines(digest, replace.lines());
      }
      case LineEdit.InsertAfter insert -> {
        digest.add("insert").add(insert.router().name()).add(insert.line());
        lines(digest, insert.lines());
      }
      case LineEdit.Append append -> {
        digest.add("append").add(append.router().name());
        lines(digest, append.lines());
      }
    }
  }

  private static void lines(ContentHash.Builder digest, List<String> lines) {
    digest.add(lines.size());
    lines.forEach(digest::add);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Transaction that && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Transaction["
        + edits.size()
        + " edits on "
        + base.keySet()
        + ", "
        + id.abbreviated()
        + "]";
  }
}
