package se.kth.sonar.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.search.Region;
import se.kth.sonar.spec.Verdict;
import se.kth.sonar.verify.Verification;

/**
 * Remembers every candidate the run has verified, by the state it was tried in. The search uses it
 * to skip candidates whose result it already knows, the prior learns from it which kinds of edits
 * repaired the network, and reports use it to explain each step. Used by one thread at a time.
 */
public final class TransitionLedger {
  private final List<Transition> transitions = new ArrayList<>();
  private final Map<ContentHash, Verification> bySnapshot = new HashMap<>();
  private final Set<ContentHash> tried = new HashSet<>();

  /**
   * One verified candidate.
   *
   * @param epoch the state the candidate was tried in
   * @param region the region the candidate came from
   * @param hypothesis the candidate
   * @param verification the verification of the result
   * @param change how the result's verdict differs from the verdict of the state
   * @param dependency a digest of what the outcome depended on: the text and the forwarding of the
   *     routers the candidate changes
   */
  public record Transition(
      int epoch,
      Region.Id region,
      Hypothesis hypothesis,
      Verification verification,
      Verdict.Change change,
      ContentHash dependency) {
    /** Checks the components. */
    public Transition {
      Objects.requireNonNull(region, "region");
      Objects.requireNonNull(hypothesis, "hypothesis");
      Objects.requireNonNull(verification, "verification");
      Objects.requireNonNull(change, "change");
      Objects.requireNonNull(dependency, "dependency");
    }

    /** A transition whose dependency is the candidate's base texts alone. */
    public Transition(
        int epoch,
        Region.Id region,
        Hypothesis hypothesis,
        Verification verification,
        Verdict.Change change) {
      this(epoch, region, hypothesis, verification, change, hypothesis.id());
    }
  }

  /** Adds a verified candidate. */
  public void add(Transition transition) {
    transitions.add(Objects.requireNonNull(transition, "transition"));
    bySnapshot.putIfAbsent(transition.verification().snapshot(), transition.verification());
    tried.add(key(transition.hypothesis(), transition.dependency()));
  }

  /** Returns whether {@code hypothesis} was verified with the same {@code dependency} before. */
  public boolean tried(Hypothesis hypothesis, ContentHash dependency) {
    return tried.contains(key(hypothesis, dependency));
  }

  private static ContentHash key(Hypothesis hypothesis, ContentHash dependency) {
    return ContentHash.builder().add(hypothesis.id()).add(dependency).build();
  }

  /** Returns the verification of the snapshot {@code snapshot}, if a candidate produced it. */
  public Optional<Verification> verificationOf(ContentHash snapshot) {
    return Optional.ofNullable(bySnapshot.get(snapshot));
  }

  /** Returns the candidates tried in state {@code epoch}, in the order they were verified. */
  public List<Transition> triedIn(int epoch) {
    return transitions.stream().filter(transition -> transition.epoch() == epoch).toList();
  }

  /** Returns every transition, in the order verified. */
  public List<Transition> all() {
    return List.copyOf(transitions);
  }

  /** Returns the number of transitions. */
  public int size() {
    return transitions.size();
  }
}
