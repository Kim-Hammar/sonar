package se.kth.sonar.prior;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.network.Topology;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.MeshRegion;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.verify.DataPlaneEvidence;

/**
 * Weights a region by the route-propagation frontiers it spans.
 *
 * <p>When a source has no route to a destination, the routers without a forwarding entry for it
 * form a connected component around the source, and that component ends exactly at the physical
 * links to routers that do have an entry or originate the destination. Each distinct no-route
 * observation, one per source and destination, counts once for every frontier link it reaches. The
 * causal program slice of that link is its two interface objects and the configuration objects they
 * reference, such as attached ACLs. A destination that is itself the subnet of a link at the
 * frontier's far end propagates from that link's own interfaces, so the slice holds them as well. A
 * region in the slice of a frontier reached by {@code n} observations weighs the Jeffreys odds
 * {@code (n + 1/2) / (1/2)}, multiplied by the finite-space reduction from every physical link to
 * the distinct frontier links the verifier observed. Other regions weigh one.
 */
public final class FrontierFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private static final double HALF = 0.5;

  private final ContentCache<Evidence> frontiers = new ContentCache<>(CACHE_CAPACITY);

  private record Evidence(Map<ProgramObject.Ref, Integer> counts, int links) {}

  @Override
  public String name() {
    return "frontier";
  }

  @Override
  public double weight(Region region, RepairState state) {
    Evidence evidence = frontiers.get(state.verification().digest(), () -> frontiers(state));
    double weight = 1;
    for (ProgramObject.Ref object : objectsOf(region)) {
      Integer count = evidence.counts().get(object);
      if (count != null) {
        double reduction =
            (double) Math.max(1, state.problem().topology().links().size())
                / Math.max(1, evidence.links());
        weight = Math.max(weight, reduction * (count + HALF) / HALF);
      }
    }
    return weight;
  }

  /** Counts the no-route observations reaching every object in a frontier link's program slice. */
  private static Evidence frontiers(RepairState state) {
    RouterEvidence owners = RouterEvidence.of(state);
    DataPlaneEvidence evidence = state.verification().evidence();
    Set<List<Object>> observed = new HashSet<>();
    Set<RouterEvidence.Link> observedLinks = new HashSet<>();
    Map<ProgramObject.Ref, Integer> counts = new HashMap<>();
    Set<ProgramObject.Ref> linkEnds = new HashSet<>();
    for (Topology.Link link : state.problem().topology().links()) {
      for (Topology.Endpoint endpoint : List.of(link.a(), link.b())) {
        linkEnds.add(
            new ProgramObject.Ref(
                "interface", endpoint.router(), List.of(endpoint.interfaceName())));
      }
    }
    for (Specification specification : state.verification().verdict().unresolved()) {
      ForwardingPredicate predicate = specification.predicate();
      if (!RouterEvidence.noRouteAtSource(evidence, predicate)
          || !observed.add(List.of(predicate.source(), predicate.destination()))) {
        continue;
      }
      for (RouterEvidence.Link link :
          owners.routeFrontier(predicate.source(), predicate.destination())) {
        observedLinks.add(link);
        Set<ProgramObject.Ref> slice = new LinkedHashSet<>(slice(state, link));
        for (ProgramObject.Ref origin : owners.origins(predicate.destination())) {
          if (linkEnds.contains(origin)
              && (origin.router().equals(link.first()) || origin.router().equals(link.second()))) {
            slice.add(origin);
          }
        }
        for (ProgramObject.Ref object : slice) {
          counts.merge(object, 1, Integer::sum);
        }
      }
    }
    return new Evidence(Map.copyOf(counts), observedLinks.size());
  }

  /** Returns the endpoint interfaces and the typed objects directly attached to them. */
  private static Set<ProgramObject.Ref> slice(RepairState state, RouterEvidence.Link frontier) {
    Set<ProgramObject.Ref> result = new LinkedHashSet<>();
    for (Topology.Link link : state.problem().topology().links()) {
      if (!frontier.equals(RouterEvidence.Link.of(link.a().router(), link.b().router()))) {
        continue;
      }
      for (Topology.Endpoint endpoint : List.of(link.a(), link.b())) {
        ProgramObject.Ref ref =
            new ProgramObject.Ref(
                "interface", endpoint.router(), List.of(endpoint.interfaceName()));
        result.add(ref);
        state.program().object(ref).ifPresent(object -> addReferences(state, object, result));
      }
    }
    return Set.copyOf(result);
  }

  /** Adds the existing objects named by the reference attributes of {@code object}. */
  private static void addReferences(
      RepairState state, ProgramObject object, Set<ProgramObject.Ref> result) {
    var type = state.program().schema().object(object.ref().type()).orElseThrow();
    for (Map.Entry<String, List<String>> entry : object.attributes().entrySet()) {
      // Every object has an implicit reference to its owning router. That is containment, not a
      // data-plane dependency: attributing a link frontier to it would make every edit anywhere on
      // either endpoint router look as precise as an edit to the endpoint itself.
      if (entry.getKey().equals("router")) {
        continue;
      }
      Attribute attribute = type.attribute(entry.getKey()).orElseThrow();
      if (!(attribute.type() instanceof Attribute.Type.Reference reference)) {
        continue;
      }
      for (String value : entry.getValue()) {
        ProgramObject.Ref attached =
            ProgramObject.Ref.isReference(value, reference.object())
                ? ProgramObject.Ref.parse(value)
                : new ProgramObject.Ref(reference.object(), object.ref().router(), List.of(value));
        if (state.program().object(attached).isPresent()) {
          result.add(attached);
        }
      }
    }
  }

  /** Returns the program objects edited by {@code region}. */
  private static Set<ProgramObject.Ref> objectsOf(Region region) {
    return switch (region) {
      case SiteRegion site -> Set.of(site.site());
      case RelationRegion relation -> Set.copyOf(relation.instance().roles().values());
      case IncidenceRegion incidence -> incidence.incidence().objects();
      case MeshRegion mesh -> mesh.mesh().objects();
      case TransferRegion transfer -> {
        Set<ProgramObject.Ref> objects = new LinkedHashSet<>();
        objects.add(transfer.transfer().policy());
        objects.addAll(transfer.transfer().blockers());
        transfer.transfer().attachment().ifPresent(item -> objects.add(item.neighbor()));
        yield Set.copyOf(objects);
      }
    };
  }
}
