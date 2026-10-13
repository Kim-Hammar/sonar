#!/usr/bin/env python3
"""
The belief trajectory of SONAR on one scenario: how the posterior over the search regions evolves
step by step, which region is searched, and how the violated specifications drop.

Usage:
    scripts/benchmark-trajectory-plot.py output/benchmark/trajectory scenario-194

Needs a run recorded with the `step` trace event (the posterior of the ten most probable regions
before each search step). Writes trajectory-<scenario>.{pdf,png,tex} to <run>/plots/: (a) the
posterior of every region that is searched at some step, with the searched region marked and the
outcome of the step; (b) the number of violated specifications after each step.
"""

import argparse
import glob
import json
import re
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
# Reference categorical palette, light mode, in fixed order.
PALETTE = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"]
TEX_PALETTE = ["blue!70!black", "orange!90!black", "teal!80!black", "yellow!60!black",
               "magenta!70!black", "green!50!black", "violet", "red!75!black"]
KINDS = {"activate": "Activate", "revise": "Revise", "bgp-session": "BGP session",
         "bgp-activation": "BGP activation"}


def load(run_dir, scenario):
    events = []
    for path in sorted(glob.glob(str(run_dir / "cells/*/*/spec-*pct/seed-*/runs/*/events.jsonl"))):
        for line in open(path):
            event = json.loads(line)
            if event["scenario"] == scenario:
                events.append(event)
    return events


def label_regions(ids):
    """Short names: the kind of the region, with a letter per object (a link, a session, ...)."""
    letters, names = {}, {}
    for region in ids:
        parts = region.split(":")
        instance = parts[-1]
        if parts[0] == "relation" and parts[1] in ("activate", "revise"):
            kind = f"{KINDS[parts[1]]} {parts[2]}"
        elif parts[0] == "relation":
            kind = KINDS.get(parts[1], parts[1].replace("-", " "))
        else:
            kind = f"{parts[0]} {parts[1]}"
        letter = letters.setdefault(instance, "ABCDEFGH"[len(letters)])
        names[region] = f"{kind} {letter}"
    return names


def trajectory(events):
    """Per step: the posterior of the top regions, the searched region, its candidates, whether a
    repair was adopted, and the violated specifications afterwards."""
    steps, violated = [], None
    for event in events:
        if event["type"] == "baseline":
            violated = event["remaining"]
        elif event["type"] == "step":
            steps.append({"posterior": event["posterior"], "region": event["region"],
                          "candidates": event["candidates"], "repaired": False,
                          "violated": violated})
        elif event["type"] == "adopted" and steps:
            steps[-1]["repaired"] = True
            violated = event["remaining"]
            steps[-1]["violated"] = violated
    return steps, events[[e["type"] for e in events].index("baseline")]["remaining"]


def segments(steps, region):
    """The runs of consecutive steps at which `region` is among the recorded top regions."""
    runs, current = [], []
    for t, s in enumerate(steps, 1):
        if region in s["posterior"]:
            current.append((t, s["posterior"][region]))
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    return runs


def plot(steps, initial, names, out, scenario):
    searched = list(dict.fromkeys(s["region"] for s in steps))
    color = {r: PALETTE[i % len(PALETTE)] for i, r in enumerate(searched)}
    xs = range(1, len(steps) + 1)
    fig, axes = plt.subplots(1, 2, figsize=(8.6, 3.0), gridspec_kw={"width_ratios": [1.5, 1]})
    ax = axes[0]
    for region in searched:
        for i, run in enumerate(segments(steps, region)):
            ax.plot([p[0] for p in run], [p[1] for p in run], color=color[region], linewidth=1.8,
                    marker="o", markersize=4, label=names[region] if i == 0 else None)
    for t, s in zip(xs, steps):
        b = s["posterior"][s["region"]]
        ax.plot(t, b, marker="o", markersize=11, markerfacecolor="none",
                markeredgecolor=color[s["region"]], markeredgewidth=1.8)
        mark = "✓" if s["repaired"] else "✗"
        ax.annotate(f"$r_t={s['candidates']}$ {mark}", (t, b), textcoords="offset points",
                    xytext=(0, 10), ha="center", fontsize=8, color=INK)
        if s["repaired"] and t < len(steps):
            ax.axvline(t + 0.5, color=MUTED, linewidth=0.8, linestyle=":")
    ax.set_xticks(list(xs))
    ax.set_xlim(0.5, len(steps) + 0.5)
    ax.set_ylim(0, 1.0)
    ax.set_xlabel("Search step $t$", fontsize=9, color=INK)
    ax.set_ylabel(r"Belief $b_t(i)$", fontsize=9, color=INK)
    ax.set_title("(a) Belief of the searched regions", fontsize=9, color=INK, loc="left")
    ax.legend(fontsize=7, frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.25), ncol=3)
    ax = axes[1]
    values = [initial] + [s["violated"] for s in steps]
    ax.step(range(0, len(steps) + 1), values, where="post", color=PALETTE[0], linewidth=1.8)
    ax.plot(range(0, len(steps) + 1), values, linestyle="none", marker="o", markersize=4,
            color=PALETTE[0])
    for t, v in enumerate(values):
        ax.annotate(str(v), (t, v), textcoords="offset points", xytext=(0, 6), ha="center",
                    fontsize=7, color=INK)
    ax.set_xticks(range(0, len(steps) + 1))
    ax.set_xlim(-0.3, len(steps) + 0.3)
    ax.set_ylim(0, initial * 1.15)
    ax.set_xlabel("Search step $t$", fontsize=9, color=INK)
    ax.set_ylabel("Violated specifications", fontsize=9, color=INK)
    ax.set_title("(b) Violated specifications", fontsize=9, color=INK, loc="left")
    for a in axes:
        for side in ("top", "right"):
            a.spines[side].set_visible(False)
        for side in ("left", "bottom"):
            a.spines[side].set_color(MUTED)
        a.tick_params(colors=MUTED, labelcolor=INK, labelsize=8)
        a.grid(True, color=GRID, linewidth=0.6)
        a.set_axisbelow(True)
    fig.tight_layout()
    for suffix in ("pdf", "png"):
        fig.savefig(out / f"trajectory-{scenario}.{suffix}", dpi=200, bbox_inches="tight")
    plt.close(fig)


def tex(steps, initial, names):
    searched = list(dict.fromkeys(s["region"] for s in steps))
    color = {r: TEX_PALETTE[i % len(TEX_PALETTE)] for i, r in enumerate(searched)}
    n = len(steps)
    lines, marks = [], []
    for region in searched:
        for i, run in enumerate(segments(steps, region)):
            coords = " ".join(f"({t},{b:.4f})" for t, b in run)
            lines.append(f"      \\addplot[thick, color={color[region]}, mark=*, mark size=1.5pt"
                         + ("" if i == 0 else ", forget plot") + f"] coordinates {{{coords}}};\n"
                         + (f"      \\addlegendentry{{{names[region]}}}\n" if i == 0 else ""))
    for t, s in enumerate(steps, 1):
        b = s["posterior"][s["region"]]
        mark = "\\checkmark" if s["repaired"] else "$\\times$"
        marks.append(f"      \\addplot[only marks, mark=o, mark size=4pt, thick,"
                     f" color={color[s['region']]}, forget plot] coordinates {{({t},{b:.4f})}};\n"
                     f"      \\node[font=\\scriptsize, above=5pt] at (axis cs:{t},{b:.4f})"
                     f" {{$r_t={s['candidates']}$ {mark}}};\n")
        if s["repaired"] and t < n:
            marks.append(f"      \\draw[gray, densely dotted] (axis cs:{t + 0.5},0) --"
                         f" (axis cs:{t + 0.5},1);\n")
    values = [initial] + [s["violated"] for s in steps]
    remaining = " ".join(f"({t},{v})" for t, v in enumerate(values))
    labels = "".join(f"      \\node[font=\\scriptsize, above=2pt] at (axis cs:{t},{v}) {{{v}}};\n"
                     for t, v in enumerate(values))
    return ("\\documentclass[tikz]{standalone}\n"
            "\\usepackage{pgfplots}\n"
            "\\usepackage{amssymb}\n"
            "\\usepgfplotslibrary{groupplots}\n"
            "\\pgfplotsset{compat=1.18}\n"
            "\\begin{document}\n"
            "\\begin{tikzpicture}\n"
            "  \\begin{groupplot}[\n"
            "      group style={group size=2 by 1, horizontal sep=1.6cm},\n"
            "      height=5.0cm,\n"
            "      grid=major, grid style={gray!25},\n"
            "      tick label style={font=\\footnotesize},\n"
            "      label style={font=\\small},\n"
            "      title style={font=\\small},\n"
            "      xlabel={Search step $t$},\n"
            "    ]\n"
            "    \\nextgroupplot[\n"
            "        width=8.0cm, title={(a) Belief of the searched regions},\n"
            f"        xmin=0.5, xmax={n + 0.5}, xtick={{1,...,{n}}}, ymin=0, ymax=1,\n"
            "        ylabel={Belief $b_t(i)$},\n"
            "        legend style={font=\\footnotesize, draw=none, at={(0.5,-0.3)}, anchor=north,"
            " legend columns=3, cells={anchor=west}},\n"
            "      ]\n" + "".join(lines) + "".join(marks) +
            "    \\nextgroupplot[\n"
            "        width=5.6cm, title={(b) Violated specifications},\n"
            f"        xmin=-0.3, xmax={n + 0.3}, xtick={{0,...,{n}}}, ymin=0,"
            f" ymax={round(initial * 1.15)},\n"
            "        ylabel={Violated specifications},\n"
            "      ]\n"
            f"      \\addplot[thick, blue!70!black, const plot, mark=*, mark size=1.5pt]"
            f" coordinates {{{remaining}}};\n" + labels +
            "  \\end{groupplot}\n"
            "\\end{tikzpicture}\n"
            "\\end{document}\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("scenario")
    args = parser.parse_args()
    steps, initial = trajectory(load(args.run_dir, args.scenario))
    names = label_regions(dict.fromkeys(s["region"] for s in steps))
    out = args.run_dir / "plots"
    out.mkdir(parents=True, exist_ok=True)
    plot(steps, initial, names, out, args.scenario)
    (out / f"trajectory-{args.scenario}.tex").write_text(tex(steps, initial, names))
    print(f"wrote trajectory-{args.scenario} ({len(steps)} steps)")
    for t, s in enumerate(steps, 1):
        print(f"  t={t} {names[s['region']]:20s} b={s['posterior'][s['region']]:.3f}"
              f" r={s['candidates']} repaired={s['repaired']} violated={s['violated']}")


if __name__ == "__main__":
    main()
