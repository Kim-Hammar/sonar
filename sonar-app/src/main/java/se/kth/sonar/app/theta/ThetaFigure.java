package se.kth.sonar.app.theta;

import java.util.List;
import java.util.Locale;

/**
 * Writes a standalone pgfplots figure that compares decision models: for each model the empirical
 * probability that the first valid repair is among the first {@code r} ranked candidates, and the
 * fitted search function {@code q(r, θ)}. The data are embedded with {@code \pgfplotstableread}, so
 * the file needs nothing else.
 */
public final class ThetaFigure {
  private static final List<String> COLORS =
      List.of("blue!70!black", "red!75!black", "green!50!black", "orange!90!black", "violet");
  private static final int MINIMUM_RANKS = 10;
  private static final int MAXIMUM_RANKS = 15;

  /**
   * One model's series.
   *
   * @param model the model's display name
   * @param observations its observed ranks
   * @param estimate its estimate of θ
   */
  public record Series(String model, RankObservations observations, ThetaEstimate estimate) {}

  private ThetaFigure() {}

  /** Returns the figure's LaTeX source for {@code series}. */
  public static String tex(List<Series> series) {
    int ranks = MINIMUM_RANKS;
    for (Series one : series) {
      for (int rank : one.observations().ranks()) {
        ranks = Math.max(ranks, Math.min(MAXIMUM_RANKS, rank));
      }
    }
    StringBuilder tex = new StringBuilder();
    tex.append("\\documentclass[tikz]{standalone}\n")
        .append("\\usepackage{pgfplots}\n")
        .append("\\usepackage{pgfplotstable}\n")
        .append("\\usepgfplotslibrary{groupplots}\n")
        .append("\\pgfplotsset{compat=1.18}\n")
        .append("\\begin{document}\n");
    for (int index = 0; index < series.size(); index++) {
      List<Integer> observed = series.get(index).observations().ranks();
      tex.append("% ")
          .append(series.get(index).model())
          .append(": the share of regions whose first valid repair is among the first r ranked")
          .append(" candidates.\n\\pgfplotstableread{\n  r share\n  0 0\n");
      for (int r = 1; r <= ranks; r++) {
        int within = r;
        long count = observed.stream().filter(rank -> rank <= within).count();
        tex.append(String.format(Locale.ROOT, "  %d %.4f%n", r, (double) count / observed.size()));
      }
      tex.append("}\\").append(table(index)).append('\n');
    }
    tex.append("% Each model's estimate of theta with its 95% confidence interval.\n")
        .append("\\pgfplotstableread{\n  index theta plus minus\n");
    double top = 0;
    for (int index = 0; index < series.size(); index++) {
      ThetaEstimate estimate = series.get(index).estimate();
      if (Double.isFinite(estimate.theta())) {
        double high = Double.isFinite(estimate.high()) ? estimate.high() : estimate.theta();
        top = Math.max(top, high);
        tex.append(
            String.format(
                Locale.ROOT,
                "  %d %.4f %.4f %.4f%n",
                index + 1,
                estimate.theta(),
                high - estimate.theta(),
                estimate.theta() - estimate.low()));
      }
    }
    tex.append("}\\estimates\n");
    StringBuilder labels = new StringBuilder();
    for (Series one : series) {
      labels.append(labels.isEmpty() ? "" : ",").append('{').append(one.model()).append('}');
    }
    tex.append("\\begin{tikzpicture}\n")
        .append("  \\begin{groupplot}[\n")
        .append("      group style={group size=2 by 1, horizontal sep=1.6cm},\n")
        .append("      height=5.2cm,\n")
        .append("      tick label style={font=\\footnotesize},\n")
        .append("      label style={font=\\small},\n")
        .append("      title style={font=\\small},\n")
        .append("    ]\n")
        .append("    \\nextgroupplot[\n")
        .append("        width=7.4cm,\n")
        .append("        title={(a) Search function $q(r,\\theta)$},\n")
        .append("        xlabel={Candidate repairs verified $r$},\n")
        .append("        ylabel={Probability},\n")
        .append(String.format(Locale.ROOT, "        xmin=0, xmax=%d, ymin=0, ymax=1.02,%n", ranks))
        .append("        grid=major, grid style={gray!25},\n")
        .append("        legend pos=south east,\n")
        .append(
            "        legend style={font=\\footnotesize, draw=none, fill=white, fill opacity=0.85,")
        .append(" text opacity=1, cells={anchor=west}},\n")
        .append("      ]\n");
    for (int index = 0; index < series.size(); index++) {
      Series one = series.get(index);
      String color = COLORS.get(index % COLORS.size());
      tex.append("      \\addplot[const plot, thick, color=")
          .append(color)
          .append("] table[x=r, y=share] {\\")
          .append(table(index))
          .append("};\n")
          .append("      \\addlegendentry{")
          .append(one.model())
          .append("}\n");
      if (Double.isFinite(one.estimate().theta())) {
        tex.append(
            String.format(
                Locale.ROOT,
                "      \\addplot[dashed, color=%s, domain=0:%d, samples=100, forget plot]"
                    + " {1-exp(-%.4f*x)};%n",
                color,
                ranks,
                one.estimate().theta()));
      }
    }
    tex.append("    \\nextgroupplot[\n")
        .append("        width=5.6cm,\n")
        .append("        title={(b) Estimated $\\hat{\\theta}$},\n")
        .append("        ylabel={$\\hat{\\theta}$},\n")
        .append(
            String.format(
                Locale.ROOT,
                "        xmin=0.4, xmax=%.1f, ymin=0, ymax=%.2f,%n",
                series.size() + 0.6,
                Math.max(0.1, top * 1.1)))
        .append(String.format(Locale.ROOT, "        xtick={1,...,%d},%n", series.size()))
        .append("        xticklabels={")
        .append(labels)
        .append("},\n")
        .append(
            "        xticklabel style={font=\\scriptsize, rotate=30, anchor=north east,"
                + " inner sep=1pt},\n")
        .append("        ymajorgrids=true, grid style={gray!25},\n")
        .append("      ]\n");
    for (int index = 0; index < series.size(); index++) {
      if (!Double.isFinite(series.get(index).estimate().theta())) {
        continue;
      }
      String color = COLORS.get(index % COLORS.size());
      tex.append(
          String.format(
              Locale.ROOT,
              "      \\addplot[ybar, bar width=0.5cm, bar shift=0pt, fill=%s, fill opacity=0.8, draw=%s,"
                  + " restrict x to domain=%d:%d,%n"
                  + "        error bars/.cd, y dir=both, y explicit, error bar style={black}]%n"
                  + "        table[x=index, y=theta, y error plus=plus, y error minus=minus]"
                  + " {\\estimates};%n",
              color,
              color,
              index + 1,
              index + 1));
    }
    tex.append("  \\end{groupplot}\n").append("\\end{tikzpicture}\n").append("\\end{document}\n");
    return tex.toString();
  }

  private static String table(int index) {
    return "model" + (char) ('A' + index);
  }
}
