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
import se.kth.sonar.generate.BgpSessionCutGenerator;
import se.kth.sonar.generate.Bounds;
import se.kth.sonar.generate.ClosureEngine;
import se.kth.sonar.generate.EditOperator;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.IdentityRefactoring;
import se.kth.sonar.generate.PolicyCutGenerator;
import se.kth.sonar.generate.ProtocolTransferGenerator;
import se.kth.sonar.generate.SeedGenerator;
import se.kth.sonar.network.Prefix;
import se.kth.sonar.program.BgpIncidenceSet;
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
  private final BgpSessionCutGenerator sessionCuts;
  private final ProtocolTransferGenerator transfers;
  private final PolicyCutGenerator policyCuts;
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
    this.sessionCuts = new BgpSessionCutGenerator();
    this.transfers = new ProtocolTransferGenerator(closure);
    this.policyCuts = new PolicyCutGenerator(seeds);
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
    for (BgpIncidenceSet incidence :
        BgpIncidenceSet.infer(program, implicated.keySet(), waypointSources)) {
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
    for (ProtocolTransfer transfer : ProtocolTransfer.infer(state)) {
      Region.Id id =
          new Region.Id("transfer:" + ContentHash.of(transfer.policy().toString()).abbreviated());
      if (!ids.add(id)) {
        continue;
      }
      RouterId router = transfer.policy().router();
      int bound =
          Math.min(
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

  /** Returns {@code region} with its admitted candidates. */
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
                yield all;
              }
              case RelationRegion relation -> {
                if (relation.goal() == RelationRegion.Goal.SUPPRESS) {
                  yield sessionCuts.generate(
                      state.program(), new BgpSessionCut(relation.instance(), relation.targets()));
                }
                Set<ClosureEngine.Obligation> obligations = new LinkedHashSet<>();
                for (Requirement requirement : relation.instance().violated()) {
                  if (requirement.signals()) {
                    obligations.add(new ClosureEngine.Obligation(relation.instance(), requirement));
                  }
                }
                yield closure.close(state.program(), state.verification(), List.of(), obligations);
              }
              case IncidenceRegion incidence ->
                  incidences.generate(state.program(), incidence.incidence());
              case TransferRegion transfer ->
                  transfers.generate(state.program(), state.verification(), transfer.transfer());
            });
    generated.addAll(IdentityRefactoring.complete(state.program(), generated));
    Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    for (Hypothesis hypothesis : generated) {
      unique.putIfAbsent(hypothesis.id(), hypothesis);
    }
    List<Hypothesis> admitted =
        new ArrayList<>(admissibility.admit(state, new ArrayList<>(unique.values())));
    admitted.sort(
        Comparator.comparing(Hypothesis::support)
            .thenComparingInt(Hypothesis::cost)
            .thenComparing(Hypothesis::id));
    if (admitted.size() > bounds.maxHypothesesPerRegion()) {
      admitted = admitted.subList(0, bounds.maxHypothesesPerRegion());
    }
    return region.withHypotheses(new Region.Hypotheses.Admitted(admitted));
  }

  /**
   * Returns the search space of {@code state}, keeping the admitted candidates of every region of
   * {@code space} that reads the same configurations and whose candidates still apply.
   */
  public SearchSpace refresh(SearchSpace space, RepairState state) {
    SearchSpace rebuilt = build(state);
    List<Region> regions = new ArrayList<>();
    for (Region region : rebuilt.regions()) {
      Optional<Region> earlier = space.region(region.id());
      if (earlier.isPresent()
          && earlier.get().reads().equals(region.reads())
          && earlier.get().hypotheses() instanceof Region.Hypotheses.Admitted admitted
          && admitted.list().stream().allMatch(h -> applies(h, state))) {
        regions.add(region.withHypotheses(admitted));
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
