package se.kth.sonar.prior;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.kth.sonar.execution.ContentCache;
import se.kth.sonar.program.AgreementOutlier;
import se.kth.sonar.program.ProgramModel;
import se.kth.sonar.program.ProgramObject;
import se.kth.sonar.program.RelationEvaluator;
import se.kth.sonar.relation.Attribute;
import se.kth.sonar.relation.ObjectType;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.state.RepairState;

/**
 * Weights sites whose value is unusual among exchangeable program relationships or objects.
 *
 * <p>An {@code agree} requirement normally detects two ends that disagree. It cannot detect a
 * jointly corrupted value: both ends still agree. This factor treats the other instances of the
 * same typed relation as exchangeable observations. Leaving the target instance out, changing its
 * current agreed value to the most frequent alternative receives Jeffreys posterior odds {@code
 * (alternative + 1/2) / (current + 1/2)}. The factor remains one unless an alternative has more
 * support, so this is full-support prior evidence rather than a fault rule. Presence attributes on
 * objects assigned to the same structural references are treated in the same way. For example, BGP
 * neighbours in one peer group are exchangeable for a boolean setting, while unrelated physical
 * interfaces are not grouped merely because they have the same object type.
 */
public final class AgreementOutlierFactor implements PriorFactor {
  private static final int CACHE_CAPACITY = 64;
  private final RelationEvaluator evaluator;
  private final ContentCache<Map<ProgramObject.Ref, Double>> factors =
      new ContentCache<>(CACHE_CAPACITY);

  /** Creates the factor using the evaluator of the program's relation schema. */
  public AgreementOutlierFactor(RelationEvaluator evaluator) {
    this.evaluator = java.util.Objects.requireNonNull(evaluator, "evaluator");
  }

  @Override
  public String name() {
    return "agreement-outlier";
  }

  @Override
  public double weight(Region region, RepairState state) {
    Map<ProgramObject.Ref, Double> weights =
        factors.get(state.program().snapshot().id(), () -> factors(state.program()));
    if (region instanceof SiteRegion site) {
      return weights.getOrDefault(site.site(), 1.0);
    }
    if (region instanceof RelationRegion relation) {
      double weight = 1;
      for (ProgramObject.Ref site : relation.instance().reads()) {
        weight = Math.max(weight, weights.getOrDefault(site, 1.0));
      }
      return weight;
    }
    return 1;
  }

  /** Computes leave-one-instance-out odds for every site that holds an agreed value. */
  private Map<ProgramObject.Ref, Double> factors(ProgramModel program) {
    Map<ProgramObject.Ref, Double> result = new HashMap<>();
    for (AgreementOutlier outlier : AgreementOutlier.infer(program, evaluator)) {
      for (ProgramObject.Ref site : outlier.sites()) {
        result.merge(site, outlier.odds(), Math::max);
      }
    }
    flagOutliers(program, result);
    return Map.copyOf(result);
  }

  private record FlagFamily(
      String type, se.kth.sonar.text.RouterId router, String references, String attribute) {}

  private record FlagObservation(ProgramObject.Ref site, boolean present) {}

  /** Adds exact beta-binomial odds for a minority boolean in a structurally homogeneous family. */
  private static void flagOutliers(ProgramModel program, Map<ProgramObject.Ref, Double> result) {
    Map<FlagFamily, List<FlagObservation>> families = new LinkedHashMap<>();
    for (ProgramObject object : program.objects()) {
      ObjectType type = program.schema().object(object.ref().type()).orElse(null);
      if (type == null || type.isRouter()) {
        continue;
      }
      String references = referenceSignature(type, object);
      if (references.isEmpty()) {
        continue;
      }
      for (Attribute attribute : type.attributes().values()) {
        if (!(attribute.type() instanceof Attribute.Type.Flag)) {
          continue;
        }
        FlagFamily family =
            new FlagFamily(type.name(), object.ref().router(), references, attribute.name());
        families
            .computeIfAbsent(family, ignored -> new ArrayList<>())
            .add(new FlagObservation(object.ref(), object.has(attribute.name())));
      }
    }
    for (List<FlagObservation> family : families.values()) {
      long present = family.stream().filter(FlagObservation::present).count();
      long absent = family.size() - present;
      if (present == 0 || absent == 0 || present == absent) {
        continue;
      }
      boolean minority = present < absent;
      long majorityCount = Math.max(present, absent);
      long minorityCount = Math.min(present, absent);
      double odds = (majorityCount + 0.5) / (minorityCount - 1 + 0.5);
      for (FlagObservation observation : family) {
        if (observation.present() == minority) {
          result.merge(observation.site(), odds, Math::max);
        }
      }
    }
  }

  /** Returns the nonempty reference-valued attributes that assign an object its structural role. */
  private static String referenceSignature(ObjectType type, ProgramObject object) {
    StringBuilder signature = new StringBuilder();
    for (Attribute attribute : type.attributes().values()) {
      if (!(attribute.type() instanceof Attribute.Type.Reference)) {
        continue;
      }
      List<String> values = object.values(attribute.name());
      if (!values.isEmpty()) {
        signature
            .append(attribute.name())
            .append('=')
            .append(String.join("\u0000", values))
            .append('\u0001');
      }
    }
    return signature.toString();
  }
}
