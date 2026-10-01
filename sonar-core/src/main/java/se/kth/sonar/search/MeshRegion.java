package se.kth.sonar.search;

import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.program.BgpMesh;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.text.RouterId;

/** A region containing the atomic closure of one verifier-required iBGP mesh incidence. */
public record MeshRegion(
    Region.Id id,
    BgpMesh mesh,
    List<Specification> targets,
    Map<RouterId, ContentHash> reads,
    Region.Hypotheses hypotheses)
    implements Region {
  /** Copies and checks the components. */
  public MeshRegion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(mesh, "mesh");
    targets = List.copyOf(targets);
    reads = Map.copyOf(reads);
    Objects.requireNonNull(hypotheses, "hypotheses");
  }

  @Override
  public NavigableSet<RouterId> routers() {
    return mesh.routers();
  }

  @Override
  public Region withHypotheses(Region.Hypotheses.Admitted admitted) {
    return new MeshRegion(id, mesh, targets, reads, admitted);
  }
}
