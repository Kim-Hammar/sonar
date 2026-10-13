#!/usr/bin/env python3
"""
The sensitivity of full SONAR (Koopman, Jev, 100 % of the specifications) to its parameters.

Usage:
    scripts/benchmark-sensitivity-plots.py [--sensitivity output/benchmark/sensitivity]
        [--default output/benchmark/timing] [--traces output/benchmark/main]

One parameter at a time is varied around the defaults θ = 0.73, λ = 100, η = 1; the default point
is the default run (`--default`, same build), the varied ones are the variants of the sensitivity
run (`sonar-koopman-theta-0.25`, ...). The verification budget needs no extra runs: the Koopman
policy's decisions do not depend on the remaining budget, so a run with budget B is the prefix of
the run with budget 300, and its outcome is read off the default traces (`extract-100.json` of
benchmark-plots.py under `--traces`).

Writes sensitivity.{pdf,png,tex} and sensitivity.csv to <sensitivity>/plots/: per parameter value
the mean fix rate, the success rate and the mean number of verifications per scenario (the figure
shows the fix rate and the verifications), with 95 % confidence
half-widths over the scenarios.
"""

import argparse
import csv
import json
import math
import statistics
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from scipy import stats  # noqa: E402

INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
LINE, LINE_TEX = "#2a78d6", "blue!70!black"
DEFAULTS = {"theta": 0.73, "lambda": 100.0, "eta": 1.0}
PARAMETERS = [
    ("theta", r"$\theta$", "\\theta", {0.25: "theta-0.25", 2.0: "theta-2.0"}, False),
    ("lambda", r"$\lambda$", "\\lambda", {10.0: "lambda-10", 1000.0: "lambda-1000"}, True),
    ("eta", r"$\eta$", "\\eta", {0.0: "eta-0", 0.5: "eta-0.5"}, False),
]
BUDGETS = [10, 25, 50, 100, 150, 200, 300]
# θ with the minimum batch of 1 of the paper's rule (6) instead of the default 5.
MIN1 = {0.25: "theta-0.25-min1", 0.73: "theta-0.73-min1", 2.0: "theta-2.0-min1"}
LINE2, LINE2_TEX = "#eb6834", "orange!90!black"


def rows(path, method):
    with open(path) as f:
        return [r for r in csv.DictReader(f)
                if r["method"] == method and r["spec_pct"] == "100" and r["status"] == "ok"]


def mean_ci(values):
    n = len(values)
    mean = statistics.mean(values)
    half = stats.t.ppf(0.975, n - 1) * statistics.stdev(values) / math.sqrt(n) if n > 1 else 0.0
    return mean, half


def point(rs):
    return {
        "n": len(rs),
        "fix": mean_ci([float(r["fix_rate"]) for r in rs]),
        "success": mean_ci([1.0 if r["success"] == "True" else 0.0 for r in rs]),
        "verifications": mean_ci([float(r["verifications"]) for r in rs]),
        "tokens": mean_ci([float(r["total_tokens"]) for r in rs]),
        "cost": mean_ci([float(r["cost_usd"]) for r in rs]),
    }


def collect(sensitivity, default):
    base = point(rows(default / "per_scenario.csv", "sonar-koopman"))
    series = {}
    for key, _, _, values, _ in PARAMETERS:
        points = {DEFAULTS[key]: base}
        for v, variant in values.items():
            points[v] = point(rows(sensitivity / "per_scenario.csv", f"sonar-koopman-{variant}"))
        series[key] = dict(sorted(points.items()))
    series["theta-min1"] = {v: point(rows(sensitivity / "per_scenario.csv",
                                          f"sonar-koopman-{variant}"))
                            for v, variant in MIN1.items()}
    return series


def budget_series(traces):
    data = json.loads((traces / "plots" / "extract-100.json").read_text())
    out = {}
    for b in BUDGETS:
        fixed, solved, used = [], [], []
        for s in data.values():
            violated = s["progress"][0][2]
            remaining = violated
            for verifications, _, left in s["progress"]:
                if verifications <= b:
                    remaining = left
            fixed.append(1.0 - remaining / violated if violated else 1.0)
            solved.append(1.0 if remaining == 0 else 0.0)
            used.append(float(min(b, s["verifications"])))
        out[b] = {"fix": mean_ci(fixed), "success": mean_ci(solved),
                  "verifications": mean_ci(used)}
    return out


def style(ax):
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(MUTED)
    ax.tick_params(colors=MUTED, labelcolor=INK, labelsize=8)
    ax.grid(True, color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)


# Fix-rate range per column: the parameters change it little, the budget a lot.
FIX_RANGE = [(0.99, 1.001), (0.99, 1.001), (0.99, 1.001), (0.6, 1.02)]


def columns_of(series, budget, tex=False):
    cols = [(key, f"${sym}$" if tex else name, list(series[key].items()), logx, DEFAULTS[key])
            for key, name, sym, _, logx in PARAMETERS]
    cols.append(("budget", "Verification budget", list(budget.items()), False, 300))
    return cols


def plot(series, budget, out):
    fig, axes = plt.subplots(2, 4, figsize=(10, 4.6))
    for col, (key, name, items, logx, default) in enumerate(columns_of(series, budget)):
        for row, measure in enumerate(("fix", "verifications")):
            ax = axes[row, col]
            groups = [(items, LINE, "Min. batch 5")]
            if key == "theta":
                groups.append((list(series["theta-min1"].items()), LINE2, "Min. batch 1"))
            for its, color, label in groups:
                ax.errorbar([x for x, _ in its], [p[measure][0] for _, p in its],
                            yerr=[p[measure][1] for _, p in its], color=color, linewidth=1.8,
                            marker="o", markersize=5, capsize=3, label=label)
            ax.axvline(default, color=MUTED, linewidth=0.8, linestyle=":")
            if logx:
                ax.set_xscale("log")
            if row == 0:
                ax.set_ylim(*FIX_RANGE[col])
            else:
                ax.set_ylim(0, 55)
                ax.set_xlabel(name, fontsize=9, color=INK)
                if col > 0:
                    ax.set_yticklabels([])
            style(ax)
        axes[0, col].set_title(f"({'abcd'[col]})", fontsize=9, color=INK, loc="left")
    axes[0, 0].set_ylabel("Fix rate", fontsize=9, color=INK)
    axes[1, 0].set_ylabel("Verifications per scenario", fontsize=9, color=INK)
    axes[0, 0].legend(fontsize=7, frameon=False, loc="lower left")
    fig.tight_layout()
    for suffix in ("pdf", "png"):
        fig.savefig(out / f"sensitivity.{suffix}", dpi=200, bbox_inches="tight")
    plt.close(fig)
    print("wrote sensitivity")


def tex(series, budget):
    tables, plots = [], []
    for row, (measure, ylabel) in enumerate((("fix", "Fix rate"),
                                             ("verifications", "Verifications per scenario"))):
        for col, (key, xlabel, items, logx, default) in enumerate(
                columns_of(series, budget, tex=True)):
            groups = [(items, LINE_TEX, "Min.\\ batch 5", "")]
            if key == "theta":
                groups.append((list(series["theta-min1"].items()), LINE2_TEX,
                               "Min.\\ batch 1", "one"))
            options = []
            if row == 0:
                lo, hi = FIX_RANGE[col]
                options.append(f"title={{({'abcd'[col]})}}")
                options.append(f"ymin={lo}, ymax={hi}")
                if col == 0:
                    options.append("legend pos=south west, legend style={font=\\scriptsize,"
                                   " draw=none, fill=none, cells={anchor=west}}")
            else:
                options.append(f"xlabel={{{xlabel}}}")
                options.append("ymin=0, ymax=55")
                if col > 0:
                    options.append("yticklabels={}")
            if col == 0:
                options.append(f"ylabel={{{ylabel}}}")
            if logx:
                options.append("xmode=log, log ticks with fixed point")
            body = []
            for its, color, label, suffix in groups:
                name = f"\\{key}{measure}{suffix}"
                tables.append(f"% {key} ({label}): mean {measure} with its 95% CI half-width.\n"
                              "\\pgfplotstableread{\n  x y e\n"
                              + "".join(f"  {x:g} {p[measure][0]:.4f} {p[measure][1]:.4f}\n"
                                        for x, p in its)
                              + f"}}{name}\n")
                body.append(f"      \\addplot[thick, color={color}, mark=*, mark size=1.8pt,"
                            " error bars/.cd, y dir=both, y explicit]\n"
                            f"        table[x=x, y=y, y error=e] {{{name}}};\n")
                if row == 0 and col == 0:
                    body.append(f"      \\addlegendentry{{{label}}}\n")
            plots.append(
                "    \\nextgroupplot[" + ", ".join(options) + "]\n" + "".join(body)
                + f"      \\draw[gray, densely dotted] ({{axis cs:{default:g},0}} |- {{rel axis cs:0,0}})"
                f" -- ({{axis cs:{default:g},0}} |- {{rel axis cs:0,1}});\n")
    return ("\\documentclass[tikz]{standalone}\n"
            "\\usepackage{pgfplots}\n"
            "\\usepackage{pgfplotstable}\n"
            "\\usepgfplotslibrary{groupplots}\n"
            "\\pgfplotsset{compat=1.18}\n"
            "\\begin{document}\n"
            + "".join(tables) +
            "\\begin{tikzpicture}\n"
            "  \\begin{groupplot}[\n"
            "      group style={group size=4 by 2, horizontal sep=1.1cm, vertical sep=1.3cm},\n"
            "      width=4.6cm, height=4.0cm,\n"
            "      grid=major, grid style={gray!25},\n"
            "      tick label style={font=\\footnotesize},\n"
            "      label style={font=\\small},\n"
            "      title style={font=\\small},\n"
            "      yticklabel style={/pgf/number format/fixed, /pgf/number format/precision=3},\n"
            "    ]\n"
            + "".join(plots) +
            "  \\end{groupplot}\n"
            "\\end{tikzpicture}\n"
            "\\end{document}\n")


def write_table(series, budget, path):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["parameter", "value", "default", "n", "fix_rate", "fix_rate_ci95", "success",
                    "success_ci95", "verifications",
                    "verifications_ci95", "total_tokens", "total_tokens_ci95", "cost_usd",
                    "cost_usd_ci95"])
        for key, _, _, _, _ in PARAMETERS:
            for v, p in series[key].items():
                w.writerow([key, f"{v:g}", v == DEFAULTS[key], p["n"],
                            *(f"{x:.6g}" for m in ("fix", "success", "verifications", "tokens",
                                                   "cost")
                              for x in p[m])])
        for v, p in series["theta-min1"].items():
            w.writerow(["theta (min. batch 1)", f"{v:g}", False, p["n"],
                        *(f"{x:.6g}" for m in ("fix", "success", "verifications", "tokens",
                                               "cost")
                          for x in p[m])])
        for b, p in budget.items():
            w.writerow(["budget", b, b == 300, "", *(f"{x:.6g}" for x in p["fix"]),
                        *(f"{x:.6g}" for x in p["success"]),
                        *(f"{x:.6g}" for x in p["verifications"]), "", "", "", ""])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sensitivity", type=Path, default=Path("output/benchmark/sensitivity"))
    parser.add_argument("--default", type=Path, default=Path("output/benchmark/timing"))
    parser.add_argument("--traces", type=Path, default=Path("output/benchmark/main"))
    args = parser.parse_args()
    out = args.sensitivity / "plots"
    out.mkdir(parents=True, exist_ok=True)
    series = collect(args.sensitivity, args.default)
    budget = budget_series(args.traces)
    plot(series, budget, out)
    (out / "sensitivity.tex").write_text(tex(series, budget))
    write_table(series, budget, out / "sensitivity.csv")
    print("wrote sensitivity.tex and sensitivity.csv")


if __name__ == "__main__":
    main()
