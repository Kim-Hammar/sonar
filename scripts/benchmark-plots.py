#!/usr/bin/env python3
"""
Plots of full SONAR (Koopman, Jev) on the benchmark, from the recorded runs of scripts/benchmark.sh.

Usage:
    scripts/benchmark-plots.py output/benchmark/<run> [--model ~typesafe/jev-latest] [--refresh]

The first call extracts what the plots need from each cell's events.jsonl and details.jsonl (the
latter are gigabytes) into <run>/plots/extract-<pct>.json; later calls reuse it unless --refresh.
Figures are written as PDF and PNG to <run>/plots/.

A scenario's record comes from the last run of the cell that finished it, as in
benchmark-export.py. Runs repair one scenario at a time, so a run's details.jsonl is split into
scenarios by its "loaded" lines, in the order of the "loaded" events in events.jsonl.
"""

import argparse
import json
import math
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

DATASET = Path("dataset/main_dataset")
SPEC_PCTS = (10, 100)
# Reference categorical palette (slots 1 and 2), light mode.
COLOR = {10: "#2a78d6", 100: "#eb6834"}
LABEL = {10: "10% specifications", 100: "100% specifications"}
INK, MUTED, GRID = "#0b0b0b", "#52514e", "#e4e3df"
MIN_NONE = 1e-12


def details_by_scenario(run):
    """Yields (scenario, list of parsed detail records of interest), one scenario at a time."""
    order = [json.loads(line)["scenario"] for line in open(run / "events.jsonl")
             if '"type":"loaded"' in line]
    wanted = ('"type":"space-built"', '"type":"decided"', '"type":"tried"', '"type":"adopted"')
    at, records = -1, []
    with open(run / "details.jsonl") as f:
        for line in f:
            head = line[:200]
            if '"type":"loaded"' in head:
                if at >= 0:
                    yield order[at], records
                at, records = at + 1, []
                continue
            if any(w in head for w in wanted):
                records.append(json.loads(line))
    if at >= 0:
        yield order[at], records


def adopted_ranks(records):
    """Ranks (prior, posterior) of the region of every adopted repair, with the space size."""
    ranks = []
    prior, odds, region_of = {}, {}, {}
    for record in records:
        kind = record["type"]
        if kind == "space-built":
            prior = {r["id"]: r["prior"] for r in record["regions"]}
            odds = {}
        elif kind == "decided" and record.get("question") == "region" \
                and record.get("outcome") == "answered":
            answer = record["answer"]
            none = max(MIN_NONE, answer["none"])
            for region in answer["regions"]:
                odds[region["id"]] = (1 - none) * region["probability"] / none
        elif kind == "tried":
            region_of[record["hypothesis"]["id"]] = record["region"]
        elif kind == "adopted" and prior:
            regions = [region_of.get(h["id"]) for h in record["hypotheses"]]
            regions = [r for r in regions if r in prior]
            if not regions:
                continue

            def rank(score, region):
                mine = score(region)
                return 1 + sum(1 for other in prior if score(other) > mine)

            p = lambda r: prior[r]  # noqa: E731
            q = lambda r: prior[r] * odds.get(r, 1.0)  # noqa: E731
            ranks.append({
                "prior": min(rank(p, r) for r in regions),
                "posterior": min(rank(q, r) for r in regions),
                "regions": len(prior),
            })
    return ranks


def regions_explored(events):
    """Distinct regions with at least one verified candidate, counted afresh in each epoch."""
    epoch, explored = 0, set()
    for event in events:
        if event["type"] in ("tried", "rejected"):
            explored.add((epoch, event["region"]))
        elif event["type"] == "adopted":
            epoch += 1
    return len(explored)


def extract(cell):
    scenarios = {}
    for run in sorted((cell / "runs").iterdir()):
        events = {}
        for line in open(run / "events.jsonl"):
            event = json.loads(line)
            events.setdefault(event["scenario"], []).append(event)
        finished = {s for s, evs in events.items() if any(e["type"] == "finished" for e in evs)}
        for scenario, records in details_by_scenario(run):
            if scenario not in finished:
                continue
            evs = events[scenario]
            loaded = next(e for e in evs if e["type"] == "loaded")
            done = next(e for e in evs if e["type"] == "finished")
            progress = [(0, 0, loaded["remaining"])]
            for e in evs:
                if e["type"] in ("adopted", "finished"):
                    progress.append((e["usage"]["verifications"], e["usage"]["decisions"],
                                     e["remaining"]))
            spaces = [e for e in evs if e["type"] == "space-built"]
            first_space = next((r for r in records if r["type"] == "space-built"), None)
            scenarios[scenario] = {
                "routers": loaded["routers"],
                "specifications": loaded["specifications"],
                "violated": loaded["remaining"],
                "stop": done["stop"],
                "adoptions": done.get("adopted", 0),
                "verifications": done["usage"]["verifications"],
                "decisions": done["usage"]["decisions"],
                "tokens": done["usage"]["inputTokens"] + done["usage"]["outputTokens"],
                "time_s": (done["at"] - evs[0]["at"]) / 1000,
                "progress": progress,
                "regions": spaces[0]["regions"] if spaces else 0,
                "candidates": sum(r["bound"] for r in first_space["regions"]) if first_space else 0,
                "ranks": adopted_ranks(records),
                "regions_explored": regions_explored(evs),
            }
            print(f"  {scenario}", flush=True)
    return scenarios


def style(ax):
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(MUTED)
    ax.tick_params(colors=MUTED, labelcolor=INK, labelsize=8)
    ax.grid(True, color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)


def save(fig, out, name):
    for suffix in ("pdf", "png"):
        fig.savefig(out / f"{name}.{suffix}", dpi=200, bbox_inches="tight")
    plt.close(fig)
    print(f"wrote {name}")


def binned_median(x, y, bins):
    centers, medians = [], []
    for lo, hi in zip(bins[:-1], bins[1:]):
        sel = [b for a, b in zip(x, y) if lo <= a < hi]
        if len(sel) >= 3:
            centers.append(math.sqrt(lo * hi))
            medians.append(float(np.median(sel)))
    return centers, medians


def plot_scaling(data, out):
    metrics = [("tokens", "Tokens per scenario"), ("verifications", "Verifications per scenario"),
               ("time_s", "Time per scenario (s)")]
    fig, axes = plt.subplots(1, 3, figsize=(10, 2.8))
    bins = np.logspace(math.log10(18), math.log10(800), 9)
    for ax, (key, title) in zip(axes, metrics):
        for pct, scen in data.items():
            points = [(s["routers"], s[key]) for s in scen.values() if s[key] > 0]
            x, y = [a for a, _ in points], [b for _, b in points]
            ax.scatter(x, y, s=10, color=COLOR[pct], alpha=0.45, linewidths=0, label=LABEL[pct])
            cx, cy = binned_median(x, y, bins)
            ax.plot(cx, cy, color=COLOR[pct], linewidth=2)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("Routers in the network", fontsize=9, color=INK)
        ax.set_title(title, fontsize=9, color=INK, loc="left")
        style(ax)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, fontsize=8, frameon=False, loc="lower center", ncol=2,
               bbox_to_anchor=(0.5, -0.06), markerscale=2)
    fig.tight_layout(rect=(0, 0.06, 1, 1))
    save(fig, out, "scaling-network-size")


def remaining_at(progress, budget, axis):
    value = progress[0][2]
    for point in progress:
        if point[axis] <= budget:
            value = point[2]
    return value / progress[0][2] if progress[0][2] else 0.0


PROGRESS_PANELS = [(0, "Verifications", 120), (1, "Decision-model calls", 30)]
PROGRESS_LABEL = "Violated specifications remaining"


def progress_curve(scen, axis, grid):
    """The mean over scenarios of the share of initially violated specifications still violated."""
    curves = np.array([[remaining_at(s["progress"], b, axis) for b in grid]
                       for s in scen.values() if s["progress"][0][2] > 0])
    return curves.mean(axis=0)


def plot_progress(data, out):
    fig, axes = plt.subplots(1, 2, figsize=(7.2, 2.8), sharey=True)
    for ax, letter, (axis, xlabel, limit) in zip(axes, "ab", PROGRESS_PANELS):
        grid = np.arange(0, limit + 1)
        for pct, scen in data.items():
            ax.step(grid, progress_curve(scen, axis, grid), where="post", color=COLOR[pct],
                    linewidth=1.8, label=LABEL[pct])
        ax.set_xlabel(xlabel, fontsize=9, color=INK)
        ax.set_title(f"({letter}) Search progress", fontsize=9, color=INK, loc="left")
        ax.set_xlim(0, limit)
        ax.set_ylim(0, 1.02)
        style(ax)
    axes[0].set_ylabel(PROGRESS_LABEL, fontsize=9, color=INK)
    axes[0].legend(fontsize=8, frameon=False, loc="upper right")
    fig.tight_layout()
    save(fig, out, "search-progress")


def progress_tex(data):
    """The search-progress figure as standalone pgfplots source, in the style of the theta figure."""
    tables, plots = [], []
    for letter, (axis, xlabel, limit) in zip("ab", PROGRESS_PANELS):
        grid = np.arange(0, limit + 1)
        body = []
        for pct, scen in data.items():
            macro = f"progress{'ab'[axis]}{'io' if pct == 10 else 'ioo'}"
            curve = progress_curve(scen, axis, grid)
            tables.append(f"% {PROGRESS_LABEL} (mean share over scenarios) after x "
                          f"{xlabel.lower()}, {pct}% specifications.\n"
                          "\\pgfplotstableread{\n  x remaining\n"
                          + "".join(f"  {x} {y:.4f}\n" for x, y in zip(grid, curve))
                          + f"}}\\{macro}\n")
            body.append(f"      \\addplot[const plot, thick, color={TEX_COLOR[pct]}]"
                        f" table[x=x, y=remaining] {{\\{macro}}};\n")
            if letter == "a":
                body.append(f"      \\addlegendentry{{{pct}\\% specifications}}\n")
        legend = ("        ylabel={" + PROGRESS_LABEL + "},\n"
                  "        legend pos=north east,\n"
                  "        legend style={font=\\footnotesize, draw=none, fill=white,"
                  " fill opacity=0.85, text opacity=1, cells={anchor=west}},\n"
                  if letter == "a" else "")
        plots.append("    \\nextgroupplot[\n"
                     f"        title={{({letter}) Search progress}},\n"
                     f"        xlabel={{{xlabel}}},\n"
                     f"        xmin=0, xmax={limit},\n"
                     + legend +
                     "      ]\n" + "".join(body))
    return ("\\documentclass[tikz]{standalone}\n"
            "\\usepackage{pgfplots}\n"
            "\\usepackage{pgfplotstable}\n"
            "\\usepgfplotslibrary{groupplots}\n"
            "\\pgfplotsset{compat=1.18}\n"
            "\\begin{document}\n"
            + "".join(tables) +
            "\\begin{tikzpicture}\n"
            "  \\begin{groupplot}[\n"
            "      group style={group size=2 by 1, horizontal sep=1.2cm, y descriptions at=edge left},\n"
            "      width=7.0cm, height=5.0cm,\n"
            "      ymin=0, ymax=1.02,\n"
            "      grid=major, grid style={gray!25},\n"
            "      tick label style={font=\\footnotesize},\n"
            "      label style={font=\\small},\n"
            "      title style={font=\\small},\n"
            "    ]\n"
            + "".join(plots) +
            "  \\end{groupplot}\n"
            "\\end{tikzpicture}\n"
            "\\end{document}\n")


def ecdf(values):
    xs = np.sort(np.asarray(values))
    return xs, np.arange(1, len(xs) + 1) / len(xs)


def plot_ranks(data, out):
    fig, axes = plt.subplots(1, 2, figsize=(8, 2.8), sharey=True)
    for ax, pct in zip(axes, data):
        ranks = [r for s in data[pct].values() for r in s["ranks"]]
        for key, color, label in [("prior", "#4a3aa7", "Prior"),
                                  ("posterior", "#1baf7a", "Prior + decision model")]:
            xs, ys = ecdf([r[key] for r in ranks])
            ax.step(xs, ys, where="post", color=color, linewidth=2, label=label)
        median_regions = int(np.median([r["regions"] for r in ranks]))
        ax.set_xlim(0.5, 10.5)
        ax.set_xticks(range(1, 11))
        ax.set_xlabel("Rank of the repaired region", fontsize=9, color=INK)
        ax.set_title(f"{LABEL[pct]} ({len(ranks)} repairs, median {median_regions} regions)",
                     fontsize=9, color=INK, loc="left")
        style(ax)
    axes[0].set_ylabel("Fraction of repairs", fontsize=9, color=INK)
    axes[0].legend(fontsize=8, frameon=False, loc="lower right")
    fig.tight_layout()
    save(fig, out, "region-rank")


def plot_explored(data, out):
    fig, ax = plt.subplots(figsize=(4.2, 2.9))
    for pct, scen in data.items():
        x = [s["candidates"] for s in scen.values() if s["candidates"]]
        y = [s["verifications"] for s in scen.values() if s["candidates"]]
        ax.scatter(x, y, s=10, color=COLOR[pct], alpha=0.5, linewidths=0, label=LABEL[pct])
    lo, hi, top = 10, 3e5, 400
    for share, text in [(1, "100%"), (0.1, "10%"), (0.01, "1%"), (0.001, "0.1%")]:
        ax.plot([lo, hi], [lo * share, hi * share], color=MUTED, linewidth=0.8, linestyle=":")
        ax.text(top / share * 0.8, top * 0.8, f"{text} verified", fontsize=7, color=MUTED,
                ha="right", va="top", rotation=0)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xlim(lo, hi)
    ax.set_ylim(1, top)
    ax.set_xlabel("Candidate repairs in the initial search space", fontsize=9, color=INK)
    ax.set_ylabel("Verifications", fontsize=9, color=INK)
    ax.legend(fontsize=8, frameon=False, loc="lower right", markerscale=2)
    style(ax)
    fig.tight_layout()
    save(fig, out, "explored-share")


def lognormal_cdf(values, grid):
    """The CDF on {@code grid} of the log-normal distribution fitted to the positive values."""
    logs = np.log([v for v in values if v > 0])
    mu, sigma = logs.mean(), max(logs.std(ddof=1), 1e-9)
    return np.array([0.5 * (1 + math.erf((math.log(x) - mu) / (sigma * math.sqrt(2))))
                     if x > 0 else 0.0 for x in grid])


def plot_effort(data, out):
    panels = [("verifications", "Verifications per scenario", True),
              ("decisions", "Decision-model calls per scenario", True),
              ("regions_explored", "Search regions explored per scenario", True),
              ("adoptions", "Repairs adopted per scenario", False)]
    fig, axes = plt.subplots(2, 2, figsize=(7.2, 5.4))
    for ax, letter, (key, xlabel, log) in zip(axes.flat, "abcd", panels):
        upper = max(s[key] for scen in data.values() for s in scen.values())
        grid = (np.logspace(0, math.log10(upper * 1.3), 400) if log
                else np.linspace(0.5, upper + 0.5, 400))
        for pct, scen in data.items():
            values = [s[key] for s in scen.values()]
            xs, ys = ecdf(values)
            xs = np.concatenate([[grid[0]], xs, [grid[-1]]])
            ys = np.concatenate([[0], ys, [1]])
            ax.step(xs, ys, where="post", color=COLOR[pct], linewidth=1.8, label=LABEL[pct])
            ax.plot(grid, lognormal_cdf(values, grid), color=COLOR[pct], linewidth=1.1,
                    linestyle=(0, (4, 2)), alpha=0.9)
        if log:
            ax.set_xscale("log")
            ticks = [t for t in (1, 2, 5, 10, 20, 50, 100, 200) if t <= grid[-1]]
            ax.set_xticks(ticks)
            ax.set_xticklabels([str(t) for t in ticks])
            ax.xaxis.set_minor_formatter(matplotlib.ticker.NullFormatter())
        else:
            ax.set_xticks(range(1, int(upper) + 1))
        ax.set_xlim(grid[0], grid[-1])
        ax.set_ylim(0, 1.03)
        ax.set_xlabel(xlabel, fontsize=9, color=INK)
        ax.set_ylabel("Probability", fontsize=9, color=INK)
        ax.set_title(f"({letter}) Empirical CDF", fontsize=9, color=INK, loc="left")
        style(ax)
    handles, labels = axes[0, 0].get_legend_handles_labels()
    handles.append(plt.Line2D([], [], color=MUTED, linewidth=1.1, linestyle=(0, (4, 2))))
    labels.append("Log-normal fit")
    fig.legend(handles, labels, fontsize=8, frameon=False, loc="lower center", ncol=3,
               bbox_to_anchor=(0.5, -0.01))
    fig.tight_layout(rect=(0, 0.04, 1, 1), h_pad=1.6)
    save(fig, out, "effort-ecdf")


TEX_COLOR = {10: "blue!70!black", 100: "red!75!black"}


def effort_tex(data):
    """The effort figure as standalone pgfplots source, in the style of the theta figure."""
    panels = [("verifications", "Verifications per scenario", True),
              ("decisions", "Decision-model calls per scenario", True),
              ("regions_explored", "Search regions explored per scenario", True),
              ("adoptions", "Repairs adopted per scenario", False)]
    tables, plots = [], []
    for letter, (key, xlabel, log) in zip("abcd", panels):
        upper = max(s[key] for scen in data.values() for s in scen.values())
        lo, hi = (1, upper * 1.3) if log else (0.5, upper + 0.5)
        grid = np.logspace(0, math.log10(hi), 120) if log else np.linspace(lo, hi, 120)
        body = []
        for pct, scen in data.items():
            values = [s[key] for s in scen.values()]
            name = f"{key.replace('_', '')}{pct}"
            xs, ys = ecdf(values)
            rows = [(lo, 0.0)]
            for x, y in zip(xs, ys):
                if x >= lo:
                    rows.append((x, y))
                else:
                    rows[0] = (lo, y)
            rows.append((hi, 1.0))
            unique = {}
            for x, y in rows:
                unique[x] = max(y, unique.get(x, 0.0))
            rows = sorted(unique.items())
            tables.append(f"% {xlabel}, {pct}% specifications: empirical CDF.\n"
                          "\\pgfplotstableread{\n  x p\n"
                          + "".join(f"  {x:g} {y:.4f}\n" for x, y in rows)
                          + f"}}\\ecdf{name.replace('0', 'o').replace('1', 'i')}\n")
            fit = lognormal_cdf(values, grid)
            tables.append(f"% {xlabel}, {pct}% specifications: log-normal fit.\n"
                          "\\pgfplotstableread{\n  x p\n"
                          + "".join(f"  {x:.4g} {y:.4f}\n" for x, y in zip(grid, fit))
                          + f"}}\\fit{name.replace('0', 'o').replace('1', 'i')}\n")
            macro = name.replace("0", "o").replace("1", "i")
            body.append(f"      \\addplot[const plot, thick, color={TEX_COLOR[pct]}]"
                        f" table[x=x, y=p] {{\\ecdf{macro}}};\n")
            if letter == "a":
                body.append(f"      \\addlegendentry{{{pct}\\% specifications}}\n")
            body.append(f"      \\addplot[dashed, color={TEX_COLOR[pct]}, forget plot]"
                        f" table[x=x, y=p] {{\\fit{macro}}};\n")
        if letter == "a":
            body.append("      \\addlegendimage{dashed, gray}\n"
                        "      \\addlegendentry{Log-normal fit}\n")
        if log:
            ticks = [t for t in (1, 2, 5, 10, 20, 50, 100, 200) if t <= hi]
            axis = (f"        xmode=log, xmin={lo:g}, xmax={hi:.4g},\n"
                    f"        xtick={{{','.join(map(str, ticks))}}},"
                    f" xticklabels={{{','.join(map(str, ticks))}}},\n"
                    "        log ticks with fixed point,\n")
        else:
            axis = (f"        xmin={lo:g}, xmax={hi:g}, xtick={{1,...,{int(upper)}}},\n")
        legend = ("        legend pos=south east,\n"
                  "        legend style={font=\\footnotesize, draw=none, fill=white,"
                  " fill opacity=0.85, text opacity=1, cells={anchor=west}},\n"
                  if letter == "a" else "")
        plots.append("    \\nextgroupplot[\n"
                     f"        title={{({letter}) Empirical CDF}},\n"
                     f"        xlabel={{{xlabel}}},\n"
                     + axis + legend +
                     "      ]\n" + "".join(body))
    return ("\\documentclass[tikz]{standalone}\n"
            "\\usepackage{pgfplots}\n"
            "\\usepackage{pgfplotstable}\n"
            "\\usepgfplotslibrary{groupplots}\n"
            "\\pgfplotsset{compat=1.18}\n"
            "\\begin{document}\n"
            + "".join(tables) +
            "\\begin{tikzpicture}\n"
            "  \\begin{groupplot}[\n"
            "      group style={group size=2 by 2, horizontal sep=1.6cm, vertical sep=2.0cm},\n"
            "      width=7.0cm, height=5.0cm,\n"
            "      ymin=0, ymax=1.02, ylabel={Probability},\n"
            "      grid=major, grid style={gray!25},\n"
            "      tick label style={font=\\footnotesize},\n"
            "      label style={font=\\small},\n"
            "      title style={font=\\small},\n"
            "    ]\n"
            + "".join(plots) +
            "  \\end{groupplot}\n"
            "\\end{tikzpicture}\n"
            "\\end{document}\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--model", default="~typesafe/jev-latest")
    parser.add_argument("--refresh", action="store_true")
    args = parser.parse_args()
    out = args.run_dir / "plots"
    out.mkdir(parents=True, exist_ok=True)
    slug = "openrouter-" + args.model.replace("/", "_").replace("~", "_")
    data = {}
    for pct in SPEC_PCTS:
        cache = out / f"extract-{pct}.json"
        if cache.exists() and not args.refresh:
            data[pct] = json.loads(cache.read_text())
            continue
        cell = args.run_dir / "cells" / "sonar-koopman" / slug / f"spec-{pct}pct" / "seed-42"
        print(f"extracting {cell}")
        data[pct] = extract(cell)
        cache.write_text(json.dumps(data[pct]))
    plot_scaling(data, out)
    plot_progress(data, out)
    plot_ranks(data, out)
    plot_explored(data, out)
    plot_effort(data, out)
    (out / "effort-ecdf.tex").write_text(effort_tex(data))
    print("wrote effort-ecdf.tex")
    (out / "search-progress.tex").write_text(progress_tex(data))
    print("wrote search-progress.tex")


if __name__ == "__main__":
    main()
