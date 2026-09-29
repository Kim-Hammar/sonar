package se.kth.sonar.network;

import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.common.NotImplemented;
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
   * Re-expresses this transaction against the result of {@code adopted}, shifting line numbers past
   * the adopted edits. Fails when the two transactions change the same lines.
   */
  public Transaction rebaseOver(Snapshot.Delta adopted) {
    throw new NotImplemented("Transaction.rebaseOver");
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
