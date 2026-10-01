package se.kth.sonar.generate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationInstance;
import se.kth.sonar.verify.Verification;

/** Materialises both endpoints of a latent IGP adjacency as one typed transaction. */
public final class IgpAdjacencyGenerator {
  private final SeedGenerator seeds;
  private final ClosureEngine closure;

  /** Creates the generator from the ordinary typed seed and closure stages. */
  public IgpAdjacencyGenerator(SeedGenerator seeds, ClosureEngine closure) {
    this.seeds = java.util.Objects.requireNonNull(seeds, "seeds");
    this.closure = java.util.Objects.requireNonNull(closure, "closure");
  }

  /**
   * Returns atomic activations of the protocols both endpoint routers already run.
   *
   * <p>The individual statements still come from the grammar's value domains. Combining the two
   * endpoint seeds before relation closure is what lets closure add the complete, mutually
   * consistent link profile when neither endpoint alone reveals the intended adjacency.
   */
  public List<Hypothesis> generate(
      ProgramModel program, Verification verification, RelationInstance link) {
    ProgramObject left = endpoint(program, link, "a");
    ProgramObject right = endpoint(program, link, "b");
    if (left == null || right == null) {
      return List.of();
    }
    Map<String, List<List<EditOperator>>> leftSeeds = activations(program, verification, left);
    Map<String, List<List<EditOperator>>> rightSeeds = activations(program, verification, right);
    Map<ContentHash, Hypothesis> unique = new LinkedHashMap<>();
    for (String production : leftSeeds.keySet()) {
      for (List<EditOperator> first : leftSeeds.get(production)) {
        for (List<EditOperator> second : rightSeeds.getOrDefault(production, List.of())) {
          List<EditOperator> seed = new ArrayList<>(first);
          seed.addAll(second);
          for (Hypothesis hypothesis : closure.close(program, verification, seed, Set.of())) {
            unique.putIfAbsent(hypothesis.id(), hypothesis);
          }
        }
      }
    }
    return List.copyOf(unique.values());
  }

  private Map<String, List<List<EditOperator>>> activations(
      ProgramModel program, Verification verification, ProgramObject endpoint) {
    Map<String, List<List<EditOperator>>> result = new LinkedHashMap<>();
    for (List<EditOperator> proposal : seeds.seeds(program, verification, endpoint.ref())) {
      if (proposal.size() != 1
          || !(proposal.getFirst() instanceof EditOperator.InsertStatement insertion)) {
        continue;
      }
      String production = insertion.production().id();
      if (production.equals("ip-ospf-area") || production.equals("ip-router-isis")) {
        result.computeIfAbsent(production, ignored -> new ArrayList<>()).add(proposal);
      }
    }
    return result;
  }

  private static ProgramObject endpoint(ProgramModel program, RelationInstance link, String role) {
    ProgramObject.Ref ref = link.roles().get(role);
    return ref == null ? null : program.object(ref).orElse(null);
  }
}
