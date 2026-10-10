#!/usr/bin/env python3
"""
Plots of where full SONAR spends its computation time, from an instrumented benchmark run.

Usage:
    scripts/benchmark-timing-plots.py output/benchmark/timing [--model ~typesafe/jev-latest] [--pct 100]

The repair engine records the duration of every operation of a scenario, grouped by phase, in a
`timings` event (field `nanos`) just before the scenario finishes. A scenario's record comes from
the last run of the cell that finished it, as in benchmark-export.py. The time of a scenario that
no phase accounts for (loading, writing the trace) is shown as "Other".

Writes computation-time.{pdf,png,tex} to <run>/plots/: (a) each phase's share of the total time
and (b) the distribution of the duration of one operation of each phase.
"""

import argparse
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
BAR, BAR_TEX = "#2a78d6", "blue!70!black"
# Phases in the order of a step, with their labels.
PHASES = [
    ("search-space", "Search regions"),
    ("prior", "Prior"),
    ("policy", "Search policy"),
    ("candidates", "Candidate generation"),
    ("decision-problem", "Prompt construction"),
    ("decision-model", "Decision model"),
    ("verification", "Verification"),
    ("adoption", "Adoption"),
    ("belief", "Belief update"),
]


def load(cell):
    """Per scenario: the durations per phase (nanoseconds) and the scenario's wall time."""
    scenarios = {}
    for run in sorted((cell / "runs").iterdir()):
        timings, first = {}, {}
        for line in open(run / "events.jsonl"):
            event = json.loads(line)
            scenario = event["scenario"]
            first.setdefault(scenario, event["at"])
            if event["type"] == "timings":
                timings[scenario] = event["nanos"]
            elif event["type"] == "finished" and scenario in timings:
                scenarios[scenario] = {
                    "nanos": timings[scenario],
                    "wall": (event["at"] - first[scenario]) * 1e6,
                }
    return scenarios


def shares(scenarios):
    totals = {phase: 0.0 for phase, _ in PHASES}
    wall = 0.0
    for s in scenarios.values():
        for phase, durations in s["nanos"].items():
            totals[phase] += sum(durations)
        wall += s["wall"]
    other = max(wall - sum(totals.values()), 0.0)
    rows = [(label, totals[phase] / wall) for phase, label in PHASES]
    rows.append(("Other", other / wall))
    return rows


def durations(scenarios, phase):
    return np.array([d for s in scenarios.values() for d in s["nanos"].get(phase, [])]) / 1e6


def style(ax):
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(MUTED)
    ax.tick_params(colors=MUTED, labelcolor=INK, labelsize=8)
    ax.grid(True, axis="x", color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)


def plot(scenarios, out):
    rows = shares(scenarios)
    fig, axes = plt.subplots(1, 2, figsize=(9.0, 3.4), sharey=True)
    ax = axes[0]
    labels = [label for label, _ in rows]
    y = np.arange(len(rows))[::-1]
    values = [100 * share for _, share in rows]
    ax.barh(y, values, height=0.6, color=BAR)
    for yi, v in zip(y, values):
        ax.text(v, yi, f" {v:.1f}%" if v >= 0.1 else f" {v:.3f}%", va="center", fontsize=7,
                color=INK)
    ax.set_yticks(y)
    ax.set_yticklabels(labels, fontsize=8)
    ax.set_xlim(0, max(values) * 1.2)
    ax.set_xlabel("Share of the total computation time (%)", fontsize=9, color=INK)
    ax.set_title("(a) Time per phase", fontsize=9, color=INK, loc="left")
    style(ax)
    ax = axes[1]
    data = [durations(scenarios, phase) for phase, _ in PHASES]
    ax.boxplot(data, positions=y[:-1], vert=False, widths=0.55, whis=(5, 95), showfliers=False,
               patch_artist=True, manage_ticks=False,
               boxprops={"facecolor": "#cfe0f6", "edgecolor": BAR},
               medianprops={"color": BAR, "linewidth": 1.8},
               whiskerprops={"color": BAR}, capprops={"color": BAR})
    ax.set_xscale("log")
    ax.set_xlabel("Duration of one operation (ms)", fontsize=9, color=INK)
    ax.set_title("(b) Duration per operation", fontsize=9, color=INK, loc="left")
    style(ax)
    fig.tight_layout()
    for suffix in ("pdf", "png"):
        fig.savefig(out / f"computation-time.{suffix}", dpi=200, bbox_inches="tight")
    plt.close(fig)
    print("wrote computation-time")


def tex(scenarios):
    """The figure as standalone pgfplots source, in the style of the other figures."""
    rows = shares(scenarios)
    n = len(rows)
    def text(share):
        v = 100 * share
        return f"{v:.1f}\\%" if v >= 0.1 else f"{v:.3f}\\%"
    share_rows = "".join(f"  {n - 1 - i} {100 * share:.4f} {text(share)}\n"
                         for i, (_, share) in enumerate(rows))
    labels = ",".join("{" + label + "}" for label, _ in reversed(rows))
    boxes = []
    for i, (phase, label) in enumerate(PHASES):
        d = durations(scenarios, phase)
        q = np.percentile(d, [5, 25, 50, 75, 95])
        boxes.append(
            f"      % {label}: 5th, 25th, 50th, 75th and 95th percentiles (ms).\n"
            f"      \\addplot[boxplot prepared={{lower whisker={q[0]:.6g}, lower quartile={q[1]:.6g},"
            f" median={q[2]:.6g}, upper quartile={q[3]:.6g}, upper whisker={q[4]:.6g},"
            f" draw position={n - 1 - i}, box extend=0.55}}, color={BAR_TEX},"
            " fill=blue!12, solid] coordinates {};\n")
    return ("\\documentclass[tikz]{standalone}\n"
            "\\usepackage{pgfplots}\n"
            "\\usepackage{pgfplotstable}\n"
            "\\usepgfplotslibrary{groupplots,statistics}\n"
            "\\pgfplotsset{compat=1.18}\n"
            "\\begin{document}\n"
            "% Share of the total computation time per phase (%).\n"
            "\\pgfplotstableread{\n  row share label\n" + share_rows + "}\\shares\n"
            "\\begin{tikzpicture}\n"
            "  \\begin{groupplot}[\n"
            "      group style={group size=2 by 1, horizontal sep=0.6cm, y descriptions at=edge left},\n"
            "      width=7.0cm, height=6.0cm,\n"
            f"      ymin=-0.6, ymax={n - 0.4}, ytick={{0,...,{n - 1}}}, yticklabels={{{labels}}},\n"
            "      xmajorgrids=true, grid style={gray!25},\n"
            "      tick label style={font=\\footnotesize},\n"
            "      label style={font=\\small},\n"
            "      title style={font=\\small},\n"
            "    ]\n"
            "    \\nextgroupplot[\n"
            "        title={(a) Time per phase},\n"
            "        xbar, bar width=0.3cm, xmin=0, xmax=75,\n"
            "        xlabel={Share of the total computation time (\\%)},\n"
            "        nodes near coords,\n"
            "        every node near coord/.style={font=\\scriptsize, anchor=west},\n"
            "        point meta=explicit symbolic,\n"
            "      ]\n"
            f"      \\addplot[fill={BAR_TEX}, draw=none] table[x=share, y=row, meta=label]"
            " {\\shares};\n"
            "    \\nextgroupplot[\n"
            "        title={(b) Duration per operation},\n"
            "        xmode=log, log ticks with fixed point,\n"
            "        xlabel={Duration of one operation (ms)},\n"
            "      ]\n"
            + "".join(boxes) +
            "  \\end{groupplot}\n"
            "\\end{tikzpicture}\n"
            "\\end{document}\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--model", default="~typesafe/jev-latest")
    parser.add_argument("--pct", type=int, default=100)
    args = parser.parse_args()
    slug = "openrouter-" + args.model.replace("/", "_").replace("~", "_")
    cell = args.run_dir / "cells" / "sonar-koopman" / slug / f"spec-{args.pct}pct" / "seed-42"
    scenarios = load(cell)
    out = args.run_dir / "plots"
    out.mkdir(parents=True, exist_ok=True)
    plot(scenarios, out)
    (out / "computation-time.tex").write_text(tex(scenarios))
    print(f"wrote computation-time.tex ({len(scenarios)} scenarios)")
    for label, share in shares(scenarios):
        print(f"  {label:22s} {100 * share:7.3f}%")


if __name__ == "__main__":
    main()
