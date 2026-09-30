package se.kth.sonar.app;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import se.kth.sonar.generate.Hypothesis;
import se.kth.sonar.generate.Provenance;
import se.kth.sonar.network.Snapshot;
import se.kth.sonar.search.IncidenceRegion;
import se.kth.sonar.search.Region;
import se.kth.sonar.search.RelationRegion;
import se.kth.sonar.search.SiteRegion;
import se.kth.sonar.search.TransferRegion;
import se.kth.sonar.text.ConfigText;
import se.kth.sonar.text.LineEdit;
import se.kth.sonar.text.SourceSpan;

/** Writes regions and candidate repairs as the JSON that run logs and the web API share. */
public final class SearchJson {
  private SearchJson() {}

  /**
   * Returns the kind of {@code region}: {@code site}, {@code relation}, {@code incidence} or {@code
   * transfer}.
   */
  public static String kind(Region region) {
    return switch (region) {
      case SiteRegion _ -> "site";
      case RelationRegion _ -> "relation";
      case IncidenceRegion _ -> "incidence";
      case TransferRegion _ -> "transfer";
    };
  }

  /** Returns {@code hypothesis} with its edits, without the text the edits remove. */
  public static ObjectNode hypothesis(Hypothesis hypothesis) {
    return hypothesis(hypothesis, Optional.empty());
  }

  /**
   * Returns {@code hypothesis} with its edits, each delete and replace carrying the lines it
   * removes from {@code base}, the network the hypothesis edits.
   */
  public static ObjectNode hypothesis(Hypothesis hypothesis, Snapshot base) {
    return hypothesis(hypothesis, Optional.of(base));
  }

  private static ObjectNode hypothesis(Hypothesis hypothesis, Optional<Snapshot> base) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("id", hypothesis.id().hex());
    ArrayNode routers = node.putArray("routers");
    hypothesis.routers().forEach(router -> routers.add(router.name()));
    node.put("cost", hypothesis.cost());
    node.put("support", hypothesis.support().name().toLowerCase(Locale.ROOT));
    ArrayNode restores = node.putArray("restores");
    for (Provenance.Restored restored : hypothesis.restores()) {
      ObjectNode entry = restores.addObject();
      entry.put("relation", restored.relation());
      entry.put("strength", restored.requirement().strength().name().toLowerCase(Locale.ROOT));
      entry.put("text", restored.requirement().text());
    }
    ArrayNode edits = node.putArray("edits");
    for (LineEdit edit : hypothesis.transaction().edits()) {
      edits.add(edit(edit, base.map(snapshot -> snapshot.text(edit.router()))));
    }
    return node;
  }

  private static ObjectNode edit(LineEdit edit, Optional<ConfigText> base) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("router", edit.router().name());
    switch (edit) {
      case LineEdit.Delete delete -> {
        node.put("op", "delete");
        span(node, delete.span(), base);
      }
      case LineEdit.Replace replace -> {
        node.put("op", "replace");
        span(node, replace.span(), base);
        lines(node, "lines", replace.lines());
      }
      case LineEdit.InsertAfter insert -> {
        node.put("op", "insert");
        node.put("after", insert.line());
        lines(node, "lines", insert.lines());
      }
      case LineEdit.Append append -> {
        node.put("op", "append");
        lines(node, "lines", append.lines());
      }
    }
    return node;
  }

  private static void span(ObjectNode node, SourceSpan span, Optional<ConfigText> base) {
    node.put("first", span.first());
    node.put("last", span.last());
    base.ifPresent(
        text -> {
          ArrayNode removed = node.putArray("removed");
          for (int line = span.first(); line <= span.last(); line++) {
            removed.add(text.line(line));
          }
        });
  }

  private static void lines(ObjectNode node, String field, List<String> lines) {
    ArrayNode array = node.putArray(field);
    lines.forEach(array::add);
  }
}
