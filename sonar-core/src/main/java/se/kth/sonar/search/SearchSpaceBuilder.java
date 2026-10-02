package se.kth.sonar.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.execution.ExecutionContext;
import se.kth.sonar.generate.Admissibility;
import se.kth.sonar.generate.BgpIncidenceGenerator;
import se.kth.sonar.generate.BgpMeshGenerator;
import se.kth.sonar.generate.BgpPolicyPathGenerator;
import se.kth.sonar.generate.BgpSessionCutGenerator;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.FirstMatchRepair;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.IdentityRefactoring;
import se.kth.sonar.generate.IgpAdjacencyGenerator;
import se.kth.sonar.generate.InterdomainTransferClosure;
import se.kth.sonar.generate.LinkProfileGenerator;
import se.kth.sonar.generate.OspfBoundaryOrigination;
import se.kth.sonar.generate.PolicyAttachmentPermutation;
import se.kth.sonar.generate.PolicyCutGenerator;
import se.kth.sonar.generate.PolicyDirectionRepair;
import se.kth.sonar.generate.ProtocolTransferGenerator;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.AgreementOutlier;
import se.kth.sonar.program.BgpIncidenceSet;
import se.kth.sonar.program.BgpMesh;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.relation.Requirement;
import se.kth.sonar.spec.ForwardingPredicate;
import se.kth.sonar.spec.Specification;
import se.kth.sonar.state.BgpSessionCut;
import se.kth.sonar.state.PolicyCut;
import se.kth.sonar.state.ProtocolTransfer;
import se.kth.sonar.state.RepairState;
import se.kth.sonar.state.RouterEvidence;
import se.kth.sonar.text.RouterId;

/**
 * Builds and maintains the search space of a repair run.
 *
 * <p>{@link #build} creates one relation region per violated relation instance and one site region
 * per program object on a router that the verifier's evidence implicates, with deferred candidates,
 * and partitions them with {@link SearchSpace#partitioned}. {@link #expand} generates and admits
 * the candidates of one region only when the search needs them, closing every seed in parallel on
 * the compute lane. {@link #refresh} rebuilds the space for a new state and keeps the candidates of
 * every region whose configurations are unchanged.
 */
public final class SearchSpaceBuilder {
  private static final int RELATION_BOUND = 16;
  private static final int SITE_BOUND_PER_STATEMENT = 2;

  private final SeedGenerator seeds;
  private final ClosureEngine closure;
  private final Admissibility admissibility;
  private final BgpIncidenceGenerator incidences;
  private final BgpPolicyPathGenerator policyPaths;
  private final IgpAdjacencyGenerator igpAdjacencies;
  private final BgpMeshGenerator meshes;
  private final BgpSessionCutGenerator sessionCuts;
  private final ProtocolTransferGenerator transfers;
  private final PolicyCutGenerator policyCuts;
  private final PolicyDirectionRepair policyDirections;
  private final ContentCache<Map<ProgramObject.Ref, Set<Prefix>>> cutTargets =
      new ContentCache<>(64);
  private final SearchSpace.Capacity capacity;
  private final Bounds bounds;
  private final ExecutionContext context;

  /** Creates a builder from the generation stages. */
  public SearchSpaceBuilder(
      SeedGenerator seeds,
      ClosureEngine closure,
      Admissibility admissibility,
      SearchSpace.Capacity capacity,
      Bounds bounds,
      ExecutionContext context) {
    this.seeds = Objects.requireNonNull(seeds, "seeds");
    this.closure = Objects.requireNonNull(closure, "closure");
    this.admissibility = Objects.requireNonNull(admissibility, "admissibility");
    this.incidences = new BgpIncidenceGenerator(seeds);
    this.policyPaths = new BgpPolicyPathGenerator(seeds);
    this.igpAdjacencies = new IgpAdjacencyGenerator(seeds, closure);
    this.meshes = new BgpMeshGenerator(seeds);
    this.sessionCuts = new BgpSessionCutGenerator();
    this.transfers = new ProtocolTransferGenerator(closure, seeds);
    this.policyCuts = new PolicyCutGenerator(seeds);
    this.policyDirections = new PolicyDirectionRepair(seeds);
    this.capacity = Objects.requireNonNull(capacity, "capacity");
    this.bounds = Objects.requireNonNull(bounds, "bounds");
    this.context = Objects.requireNonNull(context, "context");
  }

  /** Returns the search space of {@code state} with every region deferred. */
  public SearchSpace build(RepairState state) {
    ProgramModel program = state.program();
    Map<RouterId, List<Specification>> implicated = RouterEvidence.of(state).unresolvedByRouter();
    List<Region> regions = new ArrayList<>();
    Set<Region.Id> ids = new HashSet<>();
    Map<ProgramObject.Ref, Set<Prefix>> missingPermits =
        cutTargets.get(state.verification().digest(), () -> PolicyCut.missingPermitTargets(state));
    for (RelationInstance instance : program.violated()) {
      Region.Id id =
          new Region.Id("relation:" + instance.relation() + ":" + instance.id().abbreviated());
      if (instance.violated().stream().noneMatch(Requirement::signals) || !ids.add(id)) {
        continue;
      }
      List<Specification> targets = new ArrayList<>();
      Map<RouterId, ContentHash> reads = new LinkedHashMap<>();
      for (RouterId router : instance.dependencyRouters()) {
        reads.put(router, state.snapshot().text(router).digest());
        implicated
            .getOrDefault(router, List.of())
            .forEach(
                s -> {
                  if (!targets.contains(s)) {
                    targets.add(s);
                  }
                });
      }
      regions.add(
          new RelationRegion(
              id, instance, targets, reads, new Region.Hypotheses.Deferred(RELATION_BOUND)));
    }
    for (RelationInstance instance : program.instances("link")) {
      if (!latentIgpAdjacency(program, instance)
          || instance.dependencyRouters().stream().noneMatch(implicated::containsKey)) {
        continue;
      }
      Region.Id id = new Region.Id("relation:activate:link:" + instance.id().abbreviated());
      if (!ids.add(id)) {
        continue;
      }
      List<Specification> targets = new ArrayList<>();
      Map<RouterId, ContentHash> reads = new LinkedHashMap<>();
      for (RouterId router : instance.dependencyRouters()) {
        reads.put(router, state.snapshot().text(router).digest());
        for (Specification specification : implicated.getOrDefault(router, List.of())) {
          if (!targets.contains(specification)) {
            targets.add(specification);
          }
        }
      }
      regions.add(
          new RelationRegion(
              id,
              instance,
              targets,
              reads,
              RelationRegion.Goal.ACTIVATE,
              new Region.Hypotheses.Deferred(RELATION_BOUND)));
    }
    for (AgreementOutlier outlier : AgreementOutlier.infer(program)) {
      RelationInstance instance = outlier.instance();
      if (instance.dependencyRouters().stream().noneMatch(implicated::containsKey)) {
        continue;
      }
      Region.Id id =
          new Region.Id(
              "relation:revise:" + instance.relation() + ":" + instance.id().abbreviated());
      if (!ids.add(id)) {
        continue;
      }
      List<Specification> targets = new ArrayList<>();
      Map<RouterId, ContentHash> reads = new LinkedHashMap<>();
      for (RouterId router : instance.dependencyRouters()) {
        reads.put(router, state.snapshot().text(router).digest());
        for (Specification specification : implicated.getOrDefault(router, List.of())) {
          if (!targets.contains(specification)) {
            targets.add(specification);
          }
        }
      }
      regions.add(
          new RelationRegion(
              id,
              instance,
              targets,
              reads,
              RelationRegion.Goal.REVISE,
              new Region.Hypotheses.Deferred(RELATION_BOUND)));
    }
    for (BgpSessionCut cut : BgpSessionCut.infer(state)) {
      RelationInstance instance = cut.session();
      Region.Id id = new Region.Id("relation:suppress:bgp-session:" + instance.id().abbreviated());
      if (!ids.add(id)) {
        continue;
      }
      Map<RouterId, ContentHash> reads = new LinkedHashMap<>();
      for (RouterId router : instance.dependencyRouters()) {
        reads.put(router, state.snapshot().text(router).digest());
      }
      regions.add(
          new RelationRegion(
              id,
              instance,
              cut.targets(),
              reads,
              RelationRegion.Goal.SUPPRESS,
              new Region.Hypotheses.Deferred(1)));
    }
    Set<RouterId> waypointSources = new LinkedHashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      if (specification.predicate() instanceof ForwardingPredicate.Waypointing waypointing) {
        waypointSources.add(waypointing.source());
      }
    }
    List<BgpIncidenceSet> incidenceSets =
        BgpIncidenceSet.infer(program, implicated.keySet(), waypointSources);
    Set<RouterId> incidenceClients = new LinkedHashSet<>();
    for (BgpIncidenceSet incidence : incidenceSets) {
      incidence.clients().forEach(client -> incidenceClients.add(client.router()));
      Region.Id id = new Region.Id("incidence:bgp:" + incidence.id().abbreviated());
      if (!ids.add(id)) {
        continue;
      }
      List<Specification> targets = new ArrayList<>();
      Map<RouterId, ContentHash> reads = new LinkedHashMap<>();
      for (RouterId router : incidence.routers()) {
        reads.put(router, state.snapshot().text(router).digest());
        for (Specification specification : implicated.getOrDefault(router, List.of())) {
          if (!targets.contains(specification)) {
            targets.add(specification);
          }
        }
      }
      int bound = Math.min(bounds.maxHypothesesPerRegion(), incidence.clients().size() + 8);
      regions.add(
          new IncidenceRegion(
              id, incidence, targets, reads, new Region.Hypotheses.Deferred(bound)));
    }
    Set<RouterId> requiredProcesses = new LinkedHashSet<>();
    for (Specification specification : state.verification().verdict().unresolved()) {
      if (specification.predicate() instanceof ForwardingPredicate.Waypointing
          && RouterEvidence.noRouteAtSource(
              state.verification().evidence(), specification.predicate())) {
        requiredProcesses.add(specification.predicate().source());
      }
    }
    for (BgpMesh mesh : BgpMesh.infer(program, implicated.keySet(), requiredProcesses)) {
      if (incidenceClients.contains(mesh.member().router())) {
        continue;
      }
      Region.Id id = new Region.Id("incidence:bgp-mesh:" + mesh.id().abbreviated());
      if (!ids.add(id)) {
        continue;
      }
      List<Specification> targets = new ArrayList<>();
      Map<RouterId, ContentHash> reads = new LinkedHashMap<>();
      for (RouterId router : mesh.routers()) {
        reads.put(router, state.snapshot().text(router).digest());
        for (Specification specification : implicated.getOrDefault(router, List.of())) {
          if (!targets.contains(specification)) {
            targets.add(specification);
          }
        }
      }
      regions.add(new MeshRegion(id, mesh, targets, reads, new Region.Hypotheses.Deferred(1)));
    }
    for (ProtocolTransfer transfer : ProtocolTransfer.infer(state)) {
      Region.Id id =
          new Region.Id(
              "transfer:"
                  + ContentHash.builder()
                      .add(transfer.policy().toString())
                      .add(transfer.attachment().map(Object::toString).orElse("protocol"))
                      .build()
                      .abbreviated());
      if (!ids.add(id)) {
        continue;
      }
      RouterId router = transfer.policy().router();
      int bound =
          transfer.attachment().isPresent()
              ? bounds.maxHypothesesPerRegion()
              : Math.min(
                  bounds.maxHypothesesPerRegion(), Math.max(1, 1 + 2 * transfer.blockers().size()));
      regions.add(
          new TransferRegion(
              id,
              transfer,
              Map.of(router, state.snapshot().text(router).digest()),
              new Region.Hypotheses.Deferred(bound)));
    }
    for (Map.Entry<RouterId, List<Specification>> entry : implicated.entrySet()) {
      RouterId router = entry.getKey();
      ContentHash digest = state.snapshot().text(router).digest();
      for (ProgramObject object : program.objects()) {
        if (!object.ref().router().equals(router)) {
          continue;
        }
        Region.Id id = new Region.Id("site:" + object.ref());
        if (!ids.add(id)) {
          continue;
        }
        int bound =
            Math.min(
                bounds.maxHypothesesPerRegion(),
                1
                    + SITE_BOUND_PER_STATEMENT * object.statements().size()
                    + missingPermits.getOrDefault(object.ref(), Set.of()).size());
        regions.add(
            new SiteRegion(
                id,
                object.ref(),
                entry.getValue(),
                Map.of(router, digest),
                new Region.Hypotheses.Deferred(bound)));
      }
    }
    return SearchSpace.partitioned(regions, capacity);
  }

  /**
   * Returns {@code region} with its admitted candidates. An edit that several analyses derive is
   * one candidate, with the strongest support any of them gives it: a larger candidate that
   * contains the edit is then never better supported than the edit alone.
   */
  public Region expand(Region region, RepairState state) {
    List<Hypothesis> generated =
        new ArrayList<>(
            switch (region) {
              case SiteRegion site -> {
                List<List<EditOperator>> proposals =
                    seeds.seeds(state.program(), state.verification(), site.site());
                List<List<Hypothesis>> closed =
                    context.compute(
                        proposals,
                        seed ->
                            closure.close(state.program(), state.verification(), seed, Set.of()));
                List<Hypothesis> all = new ArrayList<>();
                closed.forEach(all::addAll);
                all.addAll(
                    policyCuts.generate(
                        state.program(),
                        site.site(),
                        cutTargets
                            .get(
                                state.verification().digest(),
                                () -> PolicyCut.missingPermitTargets(state))
                            .getOrDefault(site.site(), Set.of())));
                all.addAll(FirstMatchRepair.generate(state.program(), site.site()));
                all.addAll(PolicyAttachmentPermutation.generate(state.program(), site.site()));
                all.addAll(policyDirections.generate(state.program(), site.site()));
                all.addAll(
                    OspfBoundaryOrigination.generate(
                        state.program(), state.verification(), site.site(), seeds));
                all.addAll(linkProfiles(state.program(), site.site()));
                all.addAll(
                    policyPaths.generate(state.program(), state.verification(), site.site()));
                all.addAll(transferClosures(state, site.site(), List.copyOf(all)));
                yield all;
              }
              case RelationRegion relation -> {
                if (relation.goal() == RelationRegion.Goal.ACTIVATE) {
                  yield igpAdjacencies.generate(
                      state.program(), state.verification(), relation.instance());
                }
                if (relation.goal() == RelationRegion.Goal.SUPPRESS) {
                  yield sessionCuts.generate(
                      state.program(), new BgpSessionCut(relation.instance(), relation.targets()));
                }
                if (relation.goal() == RelationRegion.Goal.REVISE) {
                  List<Hypothesis> alternatives = new ArrayList<>();
                  for (AgreementOutlier outlier : AgreementOutlier.infer(state.program())) {
                    if (!outlier.instance().id().equals(relation.instance().id())) {
                      continue;
                    }
                    alternatives.addAll(
                        closure.reviseAgreement(
                            state.program(),
                            state.verification(),
                            outlier.instance(),
                            outlier.requirement(),
                            outlier.target()));
                  }
                  yield alternatives;
                }
                if (relation.instance().relation().equals("address-identity")
                    || relation.instance().relation().equals("router-identity")) {
                  yield identityRepairs(state, relation.instance());
                }
                Set<ClosureEngine.Obligation> obligations = new LinkedHashSet<>();
                for (Requirement requirement : relation.instance().violated()) {
                  if (requirement.signals()) {
                    obligations.add(new ClosureEngine.Obligation(relation.instance(), requirement));
                  }
                }
                List<Hypothesis> all = new ArrayList<>();
                if (relation.instance().relation().equals("link")) {
                  // A link is one object: preserve its complete, evidence-supported profile before
                  // the generic closure enumerates individually valid but unrelated IGP coverage.
                  all.addAll(LinkProfileGenerator.generate(state.program(), relation.instance()));
                }
                all.addAll(
                    closure.close(state.program(), state.verification(), List.of(), obligations));
                if (relation.instance().relation().equals("link")) {
                  all.addAll(
                      InterdomainTransferClosure.closeLink(
                          state.program(), relation.instance(), List.copyOf(all)));
                }
                if (relation.instance().relation().equals("route-map-liveness")) {
                  all.addAll(policyPermutations(state.program(), relation.instance()));
                }
                if (relation.instance().relation().equals("bgp-policy")) {
                  ProgramObject.Ref neighbor = relation.instance().roles().get("neighbor");
                  all.addAll(PolicyAttachmentPermutation.generate(state.program(), neighbor));
                  all.addAll(policyDirections.generate(state.program(), neighbor));
                }
                yield all;
              }
              case IncidenceRegion incidence ->
                  incidences.generate(state.program(), incidence.incidence());
              case MeshRegion mesh -> meshes.generate(state.program(), mesh.mesh());
              case TransferRegion transfer ->
                  transfers.generate(state.program(), state.verification(), transfer.transfer());
            });
    generated =
        new ArrayList<>(
            IdentityRefactoring.closeAll(state.program(), state.verification(), generated));
    Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    for (Hypothesis hypothesis : generated) {
      unique.merge(
          hypothesis.id(),
          hypothesis,
          (first, later) -> later.support().compareTo(first.support()) < 0 ? later : first);
    }
    List<Hypothesis> admitted =
        new ArrayList<>(admissibility.admit(state, new ArrayList<>(unique.values())));
    Comparator<Hypothesis> order = Comparator.comparing(Hypothesis::support);
    if (identityRegion(region)) {
      order =
          order.thenComparing(
              Comparator.comparingInt((Hypothesis hypothesis) -> hypothesis.routers().size())
                  .reversed());
    }
    admitted.sort(order.thenComparingInt(Hypothesis::cost));
    if (admitted.size() > bounds.maxHypothesesPerRegion()) {
      admitted = admitted.subList(0, bounds.maxHypothesesPerRegion());
    }
    return region.withHypotheses(new Region.Hypotheses.Admitted(admitted));
  }

  /** Makes a direct policy/filter transfer repair reachable from either object region. */
  private List<Hypothesis> transferClosures(
      RepairState state, ProgramObject.Ref site, List<Hypothesis> localCandidates) {
    if (!site.type().equals("bgp-neighbor")
        && (!site.type().equals("acl")
            || FirstMatchRepair.generate(state.program(), site).isEmpty())) {
      return List.of();
    }
    List<Hypothesis> result = new ArrayList<>();
    for (ProgramObject.Ref neighbor :
        InterdomainTransferClosure.relatedNeighbors(state.program(), site)) {
      List<Hypothesis> policies;
      if (neighbor.equals(site)) {
        policies = localCandidates;
      } else {
        List<List<EditOperator>> proposals =
            seeds.seeds(state.program(), state.verification(), neighbor);
        policies = new ArrayList<>();
        context
            .compute(
                proposals,
                seed -> closure.close(state.program(), state.verification(), seed, Set.of()))
            .forEach(policies::addAll);
        policies.addAll(PolicyAttachmentPermutation.generate(state.program(), neighbor));
        policies.addAll(policyPaths.generate(state.program(), state.verification(), neighbor));
      }
      result.addAll(InterdomainTransferClosure.close(state.program(), neighbor, policies));
    }
    return List.copyOf(result);
  }

  /** Returns the physical-link profiles that repair an implicated interface. */
  private static List<Hypothesis> linkProfiles(
      ProgramModel program, ProgramObject.Ref interfaceSite) {
    if (!interfaceSite.type().equals("interface")) {
      return List.of();
    }
    List<Hypothesis> result = new ArrayList<>();
    for (RelationInstance link : program.instances("link")) {
      if (link.roles().values().contains(interfaceSite)) {
        result.addAll(LinkProfileGenerator.generate(program, link));
      }
    }
    return List.copyOf(result);
  }

  /** Returns attachment permutations related to an unused route map on the same router. */
  private static List<Hypothesis> policyPermutations(
      ProgramModel program, RelationInstance liveness) {
    ProgramObject.Ref map = liveness.roles().get("map");
    if (map == null) {
      return List.of();
    }
    List<Hypothesis> result = new ArrayList<>();
    for (ProgramObject object : program.objectsOf(map.router())) {
      if (object.ref().type().equals("bgp-neighbor")) {
        result.addAll(PolicyAttachmentPermutation.generate(program, object.ref()));
      }
    }
    return List.copyOf(result);
  }

  /** Returns whether breadth denotes dependency completeness rather than speculative scope. */
  private static boolean identityRegion(Region region) {
    return region instanceof RelationRegion relation
        && (relation.instance().relation().equals("address-identity")
            || relation.instance().relation().equals("router-identity"));
  }

  /**
   * Returns whether a physical link hides an optional adjacency because both endpoints are absent.
   * Both routers must already run the same IGP; no protocol or topology is invented.
   */
  private static boolean latentIgpAdjacency(ProgramModel program, RelationInstance link) {
    ProgramObject.Ref leftRef = link.roles().get("a");
    ProgramObject.Ref rightRef = link.roles().get("b");
    if (leftRef == null || rightRef == null) {
      return false;
    }
    ProgramObject left = program.object(leftRef).orElse(null);
    ProgramObject right = program.object(rightRef).orElse(null);
    if (left == null || right == null || left.has("igp-enabled") || right.has("igp-enabled")) {
      return false;
    }
    ProgramObject leftRouter =
        program.object(new ProgramObject.Ref("router", leftRef.router(), List.of())).orElse(null);
    ProgramObject rightRouter =
        program.object(new ProgramObject.Ref("router", rightRef.router(), List.of())).orElse(null);
    return leftRouter != null
        && rightRouter != null
        && ((leftRouter.has("ospf-process") && rightRouter.has("ospf-process"))
            || (leftRouter.has("isis-process") && rightRouter.has("isis-process")));
  }

  /**
   * Returns dependency-closed renames for a violated router or address identity relation.
   *
   * <p>A {@code distinct} constraint identifies a collision but intentionally has no arbitrary
   * completion in the generic closure engine. Its concrete edit site is Loopback0, and the typed
   * value domain supplies candidate identities from the formal specification and the program. The
   * identity refactoring then follows every definition-use edge atomically.
   */
  private List<Hypothesis> identityRepairs(RepairState state, RelationInstance instance) {
    List<ProgramObject.Ref> sites = new ArrayList<>();
    if (instance.relation().equals("address-identity")) {
      ProgramObject.Ref site = instance.roles().get("interface");
      ProgramObject collision = site == null ? null : state.program().object(site).orElse(null);
      if (collision != null) {
        for (ProgramObject object : state.program().objects("interface")) {
          if (object.values("address").equals(collision.values("address"))) {
            sites.add(object.ref());
          }
        }
      }
    } else {
      ProgramObject.Ref router = instance.roles().get("router");
      if (router != null) {
        sites.add(new ProgramObject.Ref("interface", router.router(), List.of("Loopback0")));
      }
    }
    List<Hypothesis> result = new ArrayList<>();
    for (ProgramObject.Ref site : sites) {
      result.addAll(identityRepairs(state, site));
    }
    return List.copyOf(result);
  }

  /** Returns the closed identity hypotheses rooted at one colliding definition. */
  private List<Hypothesis> identityRepairs(RepairState state, ProgramObject.Ref site) {
    List<List<EditOperator>> proposals =
        new ArrayList<>(seeds.seeds(state.program(), state.verification(), site));
    proposals.addAll(IdentityRefactoring.consensusSeeds(state.program(), site));
    List<List<EditOperator>> verificationSeeds =
        IdentityRefactoring.verificationSeeds(state.program(), state.verification(), site);
    List<List<Hypothesis>> closed =
        context.compute(
            proposals,
            seed -> closure.close(state.program(), state.verification(), seed, Set.of()));
    List<Hypothesis> base = new ArrayList<>();
    for (List<EditOperator> seed : verificationSeeds) {
      validHypothesis(state, seed).ifPresent(base::add);
    }
    closed.forEach(base::addAll);
    return IdentityRefactoring.complete(state.program(), state.verification(), base);
  }

  /** Returns a seed as a hypothesis unless adopted edits made its spans invalid. */
  private static Optional<Hypothesis> validHypothesis(RepairState state, List<EditOperator> seed) {
    try {
      return Optional.of(Hypothesis.of(state.snapshot(), seed));
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  /**
   * Returns the search space of {@code state}, keeping the admitted candidates of every region of
   * {@code space} that reads the same configurations and whose candidates still apply: all of them,
   * those tried in the earlier state included, since the network they are tried against has
   * changed.
   */
  public SearchSpace refresh(SearchSpace space, RepairState state) {
    SearchSpace rebuilt = build(state);
    List<Region> regions = new ArrayList<>();
    for (Region region : rebuilt.regions()) {
      Optional<Region> earlier = space.region(region.id());
      if (earlier.isPresent()
          && earlier.get().reads().equals(region.reads())
          && earlier.get().hypotheses() instanceof Region.Hypotheses.Admitted admitted
          && admitted.restored().list().stream().allMatch(h -> applies(h, state))) {
        regions.add(region.withHypotheses(admitted.restored()));
      } else {
        regions.add(region);
      }
    }
    return SearchSpace.partitioned(regions, capacity);
  }

  private static boolean applies(Hypothesis hypothesis, RepairState state) {
    return hypothesis.transaction().base().entrySet().stream()
        .allMatch(entry -> state.snapshot().text(entry.getKey()).digest().equals(entry.getValue()));
  }
}
