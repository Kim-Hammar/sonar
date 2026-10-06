#!/usr/bin/env python3
"""
Exports a sonar benchmark run (scripts/benchmark.sh) in the format of the Cornetto comparison
benchmark (src/benchmark/compare.py in the cornetto repository): per_scenario.csv,
summary_by_seed.csv, summary.csv and summary.json, with the same columns, so that sonar's rows
can be concatenated with the LLM baselines.

Usage:
    scripts/benchmark-export.py output/benchmark/<run>
    scripts/benchmark-export.py output/benchmark/<run> --baseline output/benchmark/baseline/per_scenario.csv

With --baseline, the baseline rows are merged with sonar's into comparison/ (per_scenario.csv,
summary_by_seed.csv, summary.csv, summary.json), and paired.csv puts every method's fix rate,
success and tokens of a scenario on one line.

Mapping of a sonar finished event onto a Cornetto row:
    fix_rate        fixed / (fixed + remaining + sideEffects)   (Cornetto's compute_fix_rate)
    regression_rate sideEffects / (fixed + remaining + sideEffects)
    success         fix_rate >= 1.0                              (Cornetto's similarity_threshold)
    llm_calls       providerCalls, agent_steps = decisions, verifications = verifications
    wall_time_s     time from the scenario's first event to its finished event
    failure_mode    "none" when solved, sonar's stop reason otherwise
Sonar does not record LLM latency, cached tokens or thinking tokens; those columns stay empty.
"""

import argparse
import csv
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "dataset" / "main_dataset"
PRICING = Path(__file__).resolve().parent / "benchmark-pricing.json"

ROW_FIELDS = [
    "method", "provider", "model", "spec_pct", "seed", "scenario", "task_id",
    "status", "failure_mode", "fix_rate", "regression_rate",
    "n_fixed", "n_unfixed", "n_regressed", "success",
    "input_tokens", "output_tokens", "total_tokens", "thinking_tokens",
    "cached_tokens", "cost_usd", "time_s", "llm_time_s", "wall_time_s",
    "llm_calls", "agent_steps", "verifications",
    "specs_shown", "specs_violated_total", "routers_included",
    "routers_excluded", "usage_source", "attempts", "error", "timestamp",
]
MEAN_FIELDS = [
    "fix_rate", "regression_rate", "success", "cost_usd", "input_tokens",
    "output_tokens", "total_tokens", "time_s", "llm_time_s", "wall_time_s",
]
TOTAL_FIELDS = [
    "cost_usd", "input_tokens", "output_tokens", "total_tokens",
    "time_s", "llm_time_s", "wall_time_s",
]
T_CRITICAL_95 = [
    12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
    2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
    2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
]


def load_pricing():
    if not PRICING.exists():
        return {}
    with open(PRICING) as f:
        return {k: v for k, v in json.load(f).items() if not k.startswith("_")}


def cost_usd(row, pricing):
    price = pricing.get(row["model"])
    if price is None or row.get("input_tokens") in (None, ""):
        return None
    cached = row.get("cached_tokens") or 0
    fresh = int(row["input_tokens"]) - int(cached)
    return (
        fresh * price["input"]
        + int(cached) * price.get("cached_input", price["input"])
        + int(row["output_tokens"]) * price["output"]
    ) / 1e6


def cell_rows(cell_dir, method, model, spec_pct, seed):
    """One row per scenario of a cell; the last finished event of a scenario wins."""
    base = {"method": method, "provider": "openrouter", "model": model,
            "spec_pct": spec_pct, "seed": seed}
    rows, attempted, failures = {}, {}, {}
    for run in sorted((cell_dir / "runs").glob("*/run.json")):
        with open(run) as f:
            document = json.load(f)
        for scenario in document.get("scenarios", []):
            if scenario:
                attempted[scenario] = document.get("status")
        first, loaded = {}, {}
        attempts = {}
        with open(run.parent / "events.jsonl") as f:
            for line in f:
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                scenario = event.get("scenario")
                if scenario is None:
                    continue
                first.setdefault(scenario, event["at"])
                if event["type"] == "loaded":
                    loaded[scenario] = event
                if event["type"] == "failed":
                    failures[scenario] = event.get("reason")
                if event["type"] != "finished":
                    continue
                usage = event["usage"]
                fixed, remaining, side = event["fixed"], event["remaining"], event["sideEffects"]
                total = fixed + remaining + side
                fix_rate = fixed / total if total else 0.0
                wall = (event["at"] - first[scenario]) / 1000
                start = loaded.get(scenario, {})
                attempts[scenario] = attempts.get(scenario, rows.get(scenario, {}).get("attempts", 0)) + 1
                rows[scenario] = {
                    **base,
                    "scenario": scenario,
                    "task_id": int(scenario.split("-")[-1]) - 1,
                    "status": "ok",
                    "failure_mode": "none" if event["stop"] == "solved" else event["stop"],
                    "fix_rate": fix_rate,
                    "regression_rate": side / total if total else 0.0,
                    "n_fixed": fixed,
                    "n_unfixed": remaining,
                    "n_regressed": side,
                    "success": fix_rate >= 1.0,
                    "input_tokens": usage["inputTokens"],
                    "output_tokens": usage["outputTokens"],
                    "total_tokens": usage["inputTokens"] + usage["outputTokens"],
                    "thinking_tokens": None,
                    "cached_tokens": 0,
                    "time_s": wall,
                    "llm_time_s": None,
                    "wall_time_s": wall,
                    "llm_calls": usage["providerCalls"],
                    "agent_steps": usage["decisions"],
                    "verifications": usage["verifications"],
                    "specs_shown": None,
                    "specs_violated_total": start.get("remaining"),
                    "routers_included": start.get("routers"),
                    "routers_excluded": 0,
                    "usage_source": "provider",
                    "attempts": attempts[scenario],
                    "error": None,
                    "timestamp": datetime.fromtimestamp(event["at"] / 1000, timezone.utc).isoformat(),
                }
    for scenario, status in attempted.items():
        if scenario not in rows:
            rows[scenario] = {
                **base, "scenario": scenario, "task_id": int(scenario.split("-")[-1]) - 1,
                "status": "error", "error": failures.get(scenario) or f"no finished event (run {status})",
            }
    for scenario_dir in sorted(DATASET.glob("scenario-*")):
        if not (scenario_dir / "specifications" / f"specifications_{spec_pct}pct.csv").exists():
            rows.setdefault(scenario_dir.name, {
                **base, "scenario": scenario_dir.name,
                "task_id": int(scenario_dir.name.split("-")[-1]) - 1, "status": "missing_spec",
            })
    return list(rows.values())


def load_sonar_rows(run_dir):
    rows = []
    for cell in sorted(run_dir.glob("cells/*/*/spec-*pct/seed-*")):
        method = cell.parents[2].name
        model_slug = cell.parents[1].name
        config = cell / "model"
        model = config.read_text().strip() if config.exists() else model_slug
        spec_pct = int(cell.parent.name[len("spec-"):-len("pct")])
        seed = int(cell.name[len("seed-"):])
        rows.extend(cell_rows(cell, method, model, spec_pct, seed))
    return rows


def load_csv_rows(path):
    rows = []
    with open(path) as f:
        for row in csv.DictReader(f):
            for key, value in row.items():
                if value == "":
                    row[key] = None
            for key in ("spec_pct", "seed", "task_id"):
                if row.get(key) is not None:
                    row[key] = int(row[key])
            if row.get("success") is not None:
                row["success"] = row["success"] == "True"
            rows.append(row)
    return rows


def across_seeds(values):
    n = len(values)
    if n == 0:
        return {"mean": None, "std": None, "ci95_low": None, "ci95_high": None}
    mean = sum(values) / n
    if n == 1:
        return {"mean": mean, "std": None, "ci95_low": None, "ci95_high": None}
    std = (sum((v - mean) ** 2 for v in values) / (n - 1)) ** 0.5
    t_value = T_CRITICAL_95[n - 2] if n - 1 <= len(T_CRITICAL_95) else 1.96
    half_width = t_value * std / n ** 0.5
    return {"mean": mean, "std": std, "ci95_low": mean - half_width, "ci95_high": mean + half_width}


def to_csv(records, fields, path):
    tmp_path = path.with_name(path.name + ".tmp")
    with open(tmp_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(records)
    os.replace(tmp_path, path)


def export(rows, out_dir, pricing):
    """Writes Cornetto's four export files for rows; same aggregation as compare.export."""
    out_dir.mkdir(parents=True, exist_ok=True)
    for row in rows:
        if row.get("cost_usd") is None:
            row["cost_usd"] = cost_usd(row, pricing)
    rows.sort(key=lambda r: (r["method"], r["provider"], r["model"], r["spec_pct"], r["seed"], r["scenario"]))

    cells = {}
    for row in rows:
        cells.setdefault((row["method"], row["provider"], row["model"], row["spec_pct"], row["seed"]), []).append(row)

    summary_by_seed = []
    for (method, provider, model, spec_pct, seed), cell in cells.items():
        done = [r for r in cell if r["status"] == "ok"]
        entry = {
            "method": method, "provider": provider, "model": model, "spec_pct": spec_pct, "seed": seed,
            "n_scenarios": len(cell),
            "n_completed": len(done),
            "n_api_error": sum(r["status"] == "api_error" for r in cell),
            "n_error": sum(r["status"] == "error" for r in cell),
            "n_missing_spec": sum(r["status"] == "missing_spec" for r in cell),
        }
        for field in MEAN_FIELDS:
            values = [float(r[field]) for r in done if r.get(field) is not None]
            name = "success_rate" if field == "success" else f"mean_{field}"
            entry[name] = sum(values) / len(values) if values else None
        for field in TOTAL_FIELDS:
            values = [float(r[field]) for r in done if r.get(field) is not None]
            entry[f"total_{field}"] = sum(values) if values else None
        summary_by_seed.append(entry)

    id_fields = ["method", "provider", "model", "spec_pct"]
    count_fields = ["n_scenarios", "n_completed", "n_api_error", "n_error", "n_missing_spec"]
    groups = {}
    for entry in summary_by_seed:
        groups.setdefault(tuple(entry[f] for f in id_fields), []).append(entry)
    summary = []
    for key, seed_entries in groups.items():
        entry = dict(zip(id_fields, key))
        entry["n_seeds"] = len(seed_entries)
        entry["seeds"] = " ".join(str(e["seed"]) for e in seed_entries)
        entry["n_completed_min"] = min(e["n_completed"] for e in seed_entries)
        entry["n_completed_max"] = max(e["n_completed"] for e in seed_entries)
        for field in [f for f in seed_entries[0] if f not in id_fields + count_fields + ["seed"]]:
            for stat, value in across_seeds([e[field] for e in seed_entries if e[field] is not None]).items():
                entry[f"{field}_{stat}"] = value
        summary.append(entry)

    to_csv(rows, ROW_FIELDS, out_dir / "per_scenario.csv")
    to_csv(summary_by_seed, list(summary_by_seed[0]) if summary_by_seed else ["method"], out_dir / "summary_by_seed.csv")
    to_csv(summary, list(summary[0]) if summary else ["method"], out_dir / "summary.csv")
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    tmp_path = out_dir / "summary.json.tmp"
    with open(tmp_path, "w") as f:
        json.dump({
            "generated_at": datetime.now().isoformat(),
            "git_commit": commit or None,
            "pricing_usd_per_1m_tokens": pricing,
            "summary": summary,
            "summary_by_seed": summary_by_seed,
        }, f, indent=2, default=str)
    os.replace(tmp_path, out_dir / "summary.json")
    return rows


def paired(rows, path):
    """One line per (spec_pct, seed, scenario) with every method's outcome side by side."""
    methods = sorted({(r["method"], r["model"]) for r in rows})
    lines = {}
    for row in rows:
        key = (row["spec_pct"], row["seed"], row["scenario"])
        line = lines.setdefault(key, {"spec_pct": key[0], "seed": key[1], "scenario": key[2]})
        prefix = f"{row['method']}[{row['model']}]"
        line[f"{prefix}.status"] = row["status"]
        line[f"{prefix}.fix_rate"] = row.get("fix_rate")
        line[f"{prefix}.success"] = row.get("success")
        line[f"{prefix}.total_tokens"] = row.get("total_tokens")
        line[f"{prefix}.wall_time_s"] = row.get("wall_time_s")
    fields = ["spec_pct", "seed", "scenario"] + [
        f"{m}[{model}].{column}" for m, model in methods
        for column in ("status", "fix_rate", "success", "total_tokens", "wall_time_s")
    ]
    to_csv([lines[k] for k in sorted(lines)], fields, path)


def main():
    parser = argparse.ArgumentParser(description="Export a sonar benchmark run in Cornetto's format")
    parser.add_argument("run_dir", type=Path)
    parser.add_argument("--baseline", type=Path, nargs="*", default=[],
                        help="Cornetto per_scenario.csv files to merge into comparison/")
    args = parser.parse_args()

    pricing = load_pricing()
    sonar_rows = export(load_sonar_rows(args.run_dir), args.run_dir, pricing)
    print(f"Exported {len(sonar_rows)} sonar rows to {args.run_dir}")
    if args.baseline:
        rows = [dict(r) for r in sonar_rows]
        for path in args.baseline:
            rows.extend(load_csv_rows(path))
        comparison = args.run_dir / "comparison"
        rows = export(rows, comparison, pricing)
        paired(rows, comparison / "paired.csv")
        print(f"Exported {len(rows)} rows of all methods to {comparison}")


if __name__ == "__main__":
    main()
