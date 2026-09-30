package se.kth.sonar.search;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.ProtocolTransfer;
import se.kth.sonar.text.RouterId;

/**
 * Candidate transactions for one verifier-observed transfer of routes between routing protocols.
 *
 * @param id the region identifier
 * @param transfer the formal transfer observation and typed policy
 * @param reads the digest of the policy router's configuration
 * @param hypotheses the candidates, or a bound on their number
 */
public record TransferRegion(
    Region.Id id,
    ProtocolTransfer transfer,
    Map<RouterId, ContentHash> reads,
    Region.Hypotheses hypotheses)
    implements Region {

  /** Copies and checks the components. */
  public TransferRegion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(transfer, "transfer");
    reads = Map.copyOf(reads);
    Objects.requireNonNull(hypotheses, "hypotheses");
  }

  @Override
  public List<Specification> targets() {
    return transfer.targets();
  }

  @Override
  public NavigableSet<RouterId> routers() {
    return Collections.unmodifiableNavigableSet(new TreeSet<>(List.of(transfer.policy().router())));
  }

  @Override
  public Region withHypotheses(Region.Hypotheses.Admitted admitted) {
    return new TransferRegion(id, transfer, reads, admitted);
  }
}
