package se.kth.sonar.decision;

import static java.util.stream.Collectors.toSet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import se.kth.sonar.common.ContentHash;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.search.Partition;
import se.kth.sonar.search.Region;

/** A question the search asks the decision model. */
public sealed interface DecisionProblem {
  /** Returns the facts shown with the question. */
  Context context();

  /** Returns a key that identifies the question by content, for caching and deduplication. */
  ContentHash key();

  /** Returns the choices, each described for the model, keyed by an identifier. */
  SequencedMap<String, String> options();

  /** Returns the number of characters in the context and the options. */
  default int size() {
    int size = context().size();
    for (Map.Entry<String, String> option : options().entrySet()) {
      size += option.getKey().length() + option.getValue().length();
    }
    return size;
  }

  /**
   * Which region of a partition holds the repair, if any.
   *
   * @param partition the partition
   * @param regions its regions, in partition order
   * @param options the regions described for the model, keyed by region identifier
   * @param context the facts shown with the question
   */
  record RegionChoice(
      Partition partition,
      List<Region> regions,
      SequencedMap<String, String> options,
      Context context)
      implements DecisionProblem {
    /** Copies the regions and the options. */
    public RegionChoice {
      Objects.requireNonNull(partition, "partition");
      regions = List.copyOf(regions);
      options = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(options));
      Objects.requireNonNull(context, "context");
      if (!options.keySet().equals(regions.stream().map(r -> r.id().value()).collect(toSet()))) {
        throw new IllegalArgumentException("the options must describe exactly the regions");
      }
    }

    @Override
    public ContentHash key() {
      ContentHash.Builder digest =
          ContentHash.builder().add("region-choice").add(partition.id().value());
      regions.forEach(region -> digest.add(region.id().value()));
      options.forEach((id, text) -> digest.add(id).add(text));
      return digest.add(context.digest()).build();
    }
  }

  /**
   * In which order to try a region's candidates.
   *
   * @param region the region
   * @param hypotheses its candidates, in generation order
   * @param options the candidates described for the model, keyed by abbreviated identifier
   * @param context the facts shown with the question
   */
  record HypothesisChoice(
      Region region,
      List<Hypothesis> hypotheses,
      SequencedMap<String, String> options,
      Context context)
      implements DecisionProblem {
    /** Copies the candidates and the options. */
    public HypothesisChoice {
      Objects.requireNonNull(region, "region");
      hypotheses = List.copyOf(hypotheses);
      options = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(options));
      Objects.requireNonNull(context, "context");
      if (!options
          .keySet()
          .equals(hypotheses.stream().map(h -> h.id().abbreviated()).collect(toSet()))) {
        throw new IllegalArgumentException("the options must describe exactly the candidates");
      }
    }

    @Override
    public ContentHash key() {
      ContentHash.Builder digest =
          ContentHash.builder().add("hypothesis-choice").add(region.id().value());
      hypotheses.forEach(hypothesis -> digest.add(hypothesis.id()));
      options.forEach((id, text) -> digest.add(id).add(text));
      return digest.add(context.digest()).build();
    }
  }

  /**
   * Facts shown with a question, in a fixed order.
   *
   * @param sections the sections
   */
  record Context(List<Section> sections) {
    /** Copies the sections. */
    public Context {
      sections = List.copyOf(sections);
    }

    /** Returns the number of characters in all titles and bodies. */
    public int size() {
      return sections.stream()
          .mapToInt(section -> section.title().length() + section.body().length())
          .sum();
    }

    /** Returns the digest of the sections. */
    public ContentHash digest() {
      ContentHash.Builder digest = ContentHash.builder();
      sections.forEach(section -> digest.add(section.title()).add(section.body()));
      return digest.build();
    }

    /**
     * One titled block of facts.
     *
     * @param title the title
     * @param body the facts
     */
    public record Section(String title, String body) {
      /** Checks the components. */
      public Section {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
      }
    }
  }
}
