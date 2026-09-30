package se.kth.sonar.state;

import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.text.RouterId;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Identifies the formal objective, configuration, and data-plane facts on which an outcome depends.
 */
public final class HypothesisDependency {
  private HypothesisDependency() {}

  /**
   * Returns a digest of the current verification state, the changed routers' text, and their
   * forwarding and BGP-route membership for every destination observed by the verifier. Including
   * the verification state prevents evidence about an edit that helped an earlier set of violated
   * specifications from being treated as evidence that it helps the remaining objective.
   */
  public static ContentHash of(Hypothesis hypothesis, RepairState state) {
    ContentHash.Builder digest = ContentHash.builder().add(state.verification().digest());
    for (RouterId router : hypothesis.routers()) {
      digest.add(router.name()).add(router(state, router));
    }
    return digest.build();
  }

  private static ContentHash router(RepairState state, RouterId router) {
    ContentHash.Builder digest = ContentHash.builder().add(state.snapshot().text(router).digest());
    DataPlaneEvidence evidence = state.verification().evidence();
    for (Prefix destination : new TreeSet<>(evidence.forwarders().keySet())) {
      if (evidence.forwarders().get(destination).contains(router)) {
        digest.add("forwards " + destination);
      }
    }
    for (Prefix destination : new TreeSet<>(evidence.routeHolders().keySet())) {
      if (evidence.routeHolders().get(destination).contains(router)) {
        digest.add("holds " + destination);
      }
    }
    return digest.build();
  }
}
