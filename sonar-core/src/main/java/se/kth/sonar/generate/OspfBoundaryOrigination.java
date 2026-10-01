package se.kth.sonar.generate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.network.Ipv4;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.verify.Verification;

/** Originates a formally required boundary subnet into the router's existing OSPF domain. */
public final class OspfBoundaryOrigination {
  private OspfBoundaryOrigination() {}

  /**
   * Returns the exact OSPF network statement for an uncovered interface named by a failed spec.
   *
   * <p>The protocol is not guessed: the owner must already run OSPF, every covered interface must
   * agree on one area, and a positive unresolved specification must name the interface subnet.
   */
  public static List<Hypothesis> generate(
      ProgramModel program,
      Verification verification,
      ProgramObject.Ref site,
      SeedGenerator seeds) {
    if (!site.type().equals("interface")) {
      return List.of();
    }
    ProgramObject endpoint = program.object(site).orElse(null);
    ProgramObject router =
        program
            .object(new ProgramObject.Ref(ObjectType.ROUTER, site.router(), List.of()))
            .orElse(null);
    Prefix subnet =
        endpoint == null ? null : endpoint.value("subnet").flatMap(Prefix::tryParse).orElse(null);
    if (endpoint == null
        || router == null
        || subnet == null
        || endpoint.has("ospf-effective-area")
        || !router.has("ospf-process")
        || !required(verification, subnet)) {
      return List.of();
    }
    Set<String> areas = new LinkedHashSet<>();
    for (ProgramObject object : program.objectsOf(site.router())) {
      if (object.ref().type().equals("interface")) {
        areas.addAll(object.values("ospf-effective-area"));
      }
    }
    if (areas.size() != 1) {
      return List.of();
    }
    Ipv4 wildcard = new Ipv4(~subnet.mask().bits());
    List<EditOperator> operators =
        seeds.insert(
            program,
            router.ref(),
            "ospf-network",
            Map.of(
                "network", List.of(subnet.network().toString()),
                "wildcard", List.of(wildcard.toString()),
                "area", List.of(areas.iterator().next())),
            new Provenance.Inferred("formal boundary-prefix origination"));
    try {
      return operators.isEmpty()
          ? List.of()
          : List.of(Hypothesis.of(program.snapshot(), operators));
    } catch (IllegalArgumentException | IllegalStateException invalid) {
      return List.of();
    }
  }

  private static boolean required(Verification verification, Prefix subnet) {
    for (Specification specification : verification.verdict().unresolved()) {
      if (specification.status().expectPresent()
          && specification.predicate().destination().equals(subnet)) {
        return true;
      }
    }
    return false;
  }
}
