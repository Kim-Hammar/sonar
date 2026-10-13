package se.kth.sonar.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import se.kth.sonar.common.ContentHash;

/** The text of one router configuration, split into lines once and identified by its digest. */
public final class ConfigText {
  private static final Pattern LINE_BREAK = Pattern.compile("\r?\n");

  private final String text;
  private final List<String> lines;
  private final boolean trailingNewline;
  private final ContentHash digest;

  private ConfigText(String text, List<String> lines, boolean trailingNewline) {
    this.text = text;
    this.lines = lines;
    this.trailingNewline = trailingNewline;
    this.digest = ContentHash.of(text);
  }

  /** Wraps {@code text}; both LF and CRLF line breaks are accepted. */
  public static ConfigText of(String text) {
    Objects.requireNonNull(text, "text");
    if (text.isEmpty()) {
      return new ConfigText(text, List.of(), false);
    }
    boolean trailing = text.endsWith("\n");
    String body = text;
    if (text.endsWith("\r\n")) {
      body = text.substring(0, text.length() - 2);
    } else if (trailing) {
      body = text.substring(0, text.length() - 1);
    }
    return new ConfigText(text, List.of(LINE_BREAK.split(body, -1)), trailing);
  }

  /** Returns the text. */
  public String text() {
    return text;
  }

  /** Returns the lines without their line breaks. */
  public List<String> lines() {
    return lines;
  }

  /** Returns line {@code number}, counted from 1. */
  public String line(int number) {
    if (number < 1 || number > lines.size()) {
      throw new IndexOutOfBoundsException("line " + number + " of " + lines.size());
    }
    return lines.get(number - 1);
  }

  /** Returns the number of lines. */
  public int lineCount() {
    return lines.size();
  }

  /** Returns the SHA-256 digest of the text. */
  public ContentHash digest() {
    return digest;
  }

  /**
   * Applies {@code edits} as if simultaneously; every line number refers to this text.
   *
   * <p>Deleted and replaced ranges must lie within the text and must not overlap, and no insertion
   * may fall strictly inside such a range. Insertions at the same position keep their order,
   * appended lines follow the last line in order, and a trailing line break is kept.
   *
   * @throws IllegalArgumentException if an edit is out of range or two edits conflict
   */
  public ConfigText apply(List<? extends LineEdit> edits) {
    if (edits.isEmpty()) {
      return this;
    }
    NavigableMap<Integer, LineEdit> ranges = new TreeMap<>();
    Map<Integer, List<String>> insertions = new HashMap<>();
    List<String> appended = new ArrayList<>();
    for (LineEdit edit : edits) {
      switch (edit) {
        case LineEdit.Delete delete -> addRange(ranges, delete.span(), delete);
        case LineEdit.Replace replace -> addRange(ranges, replace.span(), replace);
        case LineEdit.InsertAfter insert -> {
          if (insert.line() > lines.size()) {
            throw new IllegalArgumentException(
                "insertion after line " + insert.line() + " of " + lines.size());
          }
          insertions
              .computeIfAbsent(insert.line(), line -> new ArrayList<>())
              .addAll(insert.lines());
        }
        case LineEdit.Append append -> appended.addAll(append.lines());
      }
    }
    checkConflicts(ranges, insertions);
    List<String> result = new ArrayList<>(lines.size() + appended.size());
    int line = 1;
    while (line <= lines.size()) {
      result.addAll(insertions.getOrDefault(line - 1, List.of()));
      LineEdit range = ranges.get(line);
      if (range == null) {
        result.add(lines.get(line - 1));
        line++;
      } else {
        SourceSpan span = spanOf(range);
        if (range instanceof LineEdit.Replace replace) {
          result.addAll(replace.lines());
        }
        line = span.last() + 1;
      }
    }
    result.addAll(insertions.getOrDefault(lines.size(), List.of()));
    result.addAll(appended);
    String joined = String.join("\n", result) + (trailingNewline ? "\n" : "");
    return new ConfigText(joined, Collections.unmodifiableList(result), trailingNewline);
  }

  private void addRange(NavigableMap<Integer, LineEdit> ranges, SourceSpan span, LineEdit edit) {
    if (span.last() > lines.size()) {
      throw new IllegalArgumentException(
          "line range " + span + " exceeds " + lines.size() + " lines");
    }
    if (ranges.putIfAbsent(span.first(), edit) != null) {
      throw new IllegalArgumentException("two edits change line " + span.first());
    }
  }

  private static void checkConflicts(
      NavigableMap<Integer, LineEdit> ranges, Map<Integer, List<String>> insertions) {
    int previousLast = 0;
    for (LineEdit edit : ranges.values()) {
      SourceSpan span = spanOf(edit);
      if (span.first() <= previousLast) {
        throw new IllegalArgumentException("edits overlap at line " + span.first());
      }
      previousLast = span.last();
    }
    for (int position : insertions.keySet()) {
      Map.Entry<Integer, LineEdit> floor = ranges.floorEntry(position);
      if (floor != null && position < spanOf(floor.getValue()).last()) {
        throw new IllegalArgumentException(
            "insertion after line " + position + " falls inside " + spanOf(floor.getValue()));
      }
    }
  }

  private static SourceSpan spanOf(LineEdit edit) {
    return switch (edit) {
      case LineEdit.Delete delete -> delete.span();
      case LineEdit.Replace replace -> replace.span();
      case LineEdit.InsertAfter insert ->
          throw new IllegalArgumentException("an insertion has no span: " + insert);
      case LineEdit.Append append ->
          throw new IllegalArgumentException("an append has no span: " + append);
    };
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ConfigText that && text.equals(that.text);
  }

  @Override
  public int hashCode() {
    return text.hashCode();
  }

  @Override
  public String toString() {
    return "ConfigText[" + lines.size() + " lines, " + digest.abbreviated() + "]";
  }
}
