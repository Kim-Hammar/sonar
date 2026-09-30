package se.kth.sonar.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the line edits that turn one configuration text into another: the unchanged prefix and
 * suffix are kept, and the lines between are aligned by their longest common subsequence, so a
 * change of a few lines becomes a few small edits.
 */
public final class LineDiff {
  private LineDiff() {}

  /** Returns edits against {@code before} whose application yields {@code after}. */
  public static List<LineEdit> between(RouterId router, ConfigText before, ConfigText after) {
    List<String> a = before.lines();
    List<String> b = after.lines();
    int prefix = 0;
    while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < a.size() - prefix
        && suffix < b.size() - prefix
        && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
      suffix++;
    }
    List<String> middleA = a.subList(prefix, a.size() - suffix);
    List<String> middleB = b.subList(prefix, b.size() - suffix);
    boolean[] keptA = new boolean[middleA.size()];
    boolean[] keptB = new boolean[middleB.size()];
    align(middleA, middleB, keptA, keptB);
    List<LineEdit> edits = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < middleA.size() || j < middleB.size()) {
      if (i < middleA.size() && j < middleB.size() && keptA[i] && keptB[j]) {
        i++;
        j++;
        continue;
      }
      int removeStart = i;
      while (i < middleA.size() && !keptA[i]) {
        i++;
      }
      List<String> added = new ArrayList<>();
      while (j < middleB.size() && !keptB[j]) {
        added.add(middleB.get(j));
        j++;
      }
      int first = prefix + removeStart + 1;
      int last = prefix + i;
      if (i > removeStart && !added.isEmpty()) {
        edits.add(new LineEdit.Replace(new SourceSpan(router, first, last), added));
      } else if (i > removeStart) {
        edits.add(new LineEdit.Delete(new SourceSpan(router, first, last)));
      } else if (prefix + removeStart == a.size()) {
        edits.add(new LineEdit.Append(router, added));
      } else {
        edits.add(new LineEdit.InsertAfter(router, prefix + removeStart, added));
      }
    }
    return edits;
  }

  private static void align(List<String> a, List<String> b, boolean[] keptA, boolean[] keptB) {
    int[][] longest = new int[a.size() + 1][b.size() + 1];
    for (int i = a.size() - 1; i >= 0; i--) {
      for (int j = b.size() - 1; j >= 0; j--) {
        longest[i][j] =
            a.get(i).equals(b.get(j))
                ? longest[i + 1][j + 1] + 1
                : Math.max(longest[i + 1][j], longest[i][j + 1]);
      }
    }
    int i = 0;
    int j = 0;
    while (i < a.size() && j < b.size()) {
      if (a.get(i).equals(b.get(j))) {
        keptA[i] = true;
        keptB[j] = true;
        i++;
        j++;
      } else if (longest[i + 1][j] >= longest[i][j + 1]) {
        i++;
      } else {
        j++;
      }
    }
  }
}
