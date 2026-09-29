package se.kth.sonar.decision;

import java.util.List;
import java.util.Objects;
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

  /**
   * Which region of a partition holds the repair, if any.
   *
   * @param partition the partition
   * @param regions its regions, in partition order
   * @param context the facts shown with the question
   */
  record RegionChoice(Partition partition, List<Region> regions, Context context)
      implements DecisionProblem {
    /** Copies the regions. */
    public RegionChoice {
      Objects.requireNonNull(partition, "partition");
      regions = List.copyOf(regions);
      Objects.requireNonNull(context, "context");
    }

    @Override
    public ContentHash key() {
      ContentHash.Builder digest =
          ContentHash.builder().add("region-choice").add(partition.id().value());
      regions.forEach(region -> digest.add(region.id().value()));
      return digest.add(context.digest()).build();
    }
  }

  /**
   * In which order to try a region's candidates.
   *
   * @param region the region
   * @param hypotheses its candidates, in generation order
   * @param context the facts shown with the question
   */
  record HypothesisChoice(Region region, List<Hypothesis> hypotheses, Context context)
      implements DecisionProblem {
    /** Copies the candidates. */
    public HypothesisChoice {
      Objects.requireNonNull(region, "region");
      hypotheses = List.copyOf(hypotheses);
      Objects.requireNonNull(context, "context");
    }

    @Override
    public ContentHash key() {
      ContentHash.Builder digest =
          ContentHash.builder().add("hypothesis-choice").add(region.id().value());
      hypotheses.forEach(hypothesis -> digest.add(hypothesis.id()));
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
