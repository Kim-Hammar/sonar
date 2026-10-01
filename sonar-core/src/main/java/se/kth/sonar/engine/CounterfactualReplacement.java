package se.kth.sonar.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.network.Transaction;
import se.kth.sonar.text.RouterId;

/** Replays a repair history with one adopted transaction replaced by a new candidate. */
final class CounterfactualReplacement {
  private CounterfactualReplacement() {}

  /**
   * A replayable alternative.
   *
   * @param replaced index of the adopted transaction omitted from the history
   * @param snapshot replayed snapshot with the candidate applied last
   * @param history retained transactions rebased into replay order, followed by the candidate
   */
  record Alternative(int replaced, Snapshot snapshot, List<Transaction> history) {
    Alternative {
      Objects.requireNonNull(snapshot, "snapshot");
      history = List.copyOf(history);
    }
  }

  /**
   * Returns histories obtained by removing one adoption and applying {@code candidate}.
   *
   * <p>Later adoptions and the candidate are rebased over the omitted change. This permits
   * independent edits of one router to survive even though the router's whole-file version changed,
   * while {@link Transaction#rebaseOver} still rejects edits of the same lines. Alternatives are
   * returned newest first because the nearest causal predecessor is the least disruptive one.
   */
  static List<Alternative> alternatives(
      Snapshot initial, List<Transaction> adopted, Hypothesis candidate) {
    List<Alternative> alternatives = new ArrayList<>();
    for (int omitted = adopted.size() - 1; omitted >= 0; omitted--) {
      Snapshot history = initial;
      Snapshot replayed = initial;
      List<Transaction> replayedHistory = new ArrayList<>();
      boolean applies = true;
      for (int at = 0; at < adopted.size(); at++) {
        Transaction transaction = adopted.get(at);
        Snapshot before = history;
        try {
          history = history.patch(transaction).after();
        } catch (IllegalStateException stale) {
          applies = false;
          break;
        }
        if (at == omitted) {
          continue;
        }
        Optional<Transaction> rebased = rebase(transaction, before, replayed);
        if (rebased.isEmpty()) {
          applies = false;
          break;
        }
        replayed = replayed.patch(rebased.get()).after();
        replayedHistory.add(rebased.get());
      }
      if (!applies) {
        continue;
      }
      Optional<Transaction> rebased = rebase(candidate.transaction(), history, replayed);
      if (rebased.isEmpty()) {
        continue;
      }
      replayed = replayed.patch(rebased.get()).after();
      replayedHistory.add(rebased.get());
      alternatives.add(new Alternative(omitted, replayed, replayedHistory));
    }
    return List.copyOf(alternatives);
  }

  /** Returns {@code transaction} expressed against {@code target}, if the edits do not conflict. */
  private static Optional<Transaction> rebase(
      Transaction transaction, Snapshot base, Snapshot target) {
    if (base.equals(target)) {
      return Optional.of(transaction);
    }
    return transaction.rebaseOver(delta(base, target));
  }

  /** Returns the content delta from {@code before} to {@code after}. */
  private static Snapshot.Delta delta(Snapshot before, Snapshot after) {
    TreeSet<RouterId> changed = new TreeSet<>();
    for (RouterId router : after.routers()) {
      if (!before.contains(router)
          || !before.text(router).digest().equals(after.text(router).digest())) {
        changed.add(router);
      }
    }
    return new Snapshot.Delta(before, after, Collections.unmodifiableNavigableSet(changed));
  }
}
