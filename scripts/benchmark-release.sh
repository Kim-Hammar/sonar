#!/usr/bin/env bash
# Usage: scripts/benchmark-release.sh [--with-traces] [RUN_DIR] [OUT_DIR] [BASELINE_DIR]
#
# Stages the RELEASED cells of a benchmark run in OUT_DIR for publication, without uploading.
# Defaults: RUN_DIR output/benchmark/main, OUT_DIR output/release/sonar-results, BASELINE_DIR
# output/benchmark/baseline/main. Rerunning updates OUT_DIR in place and keeps its README.md.
#
# Only metrics are staged by default, since the traces hold the benchmark's repairs and must not end
# up in training data. --with-traces adds them under results/, for sharing on request.
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$root"

with_traces=false
if [ "${1:-}" = "--with-traces" ]; then
  with_traces=true
  shift
fi
run_dir="${1:-output/benchmark/main}"
out_dir="${2:-output/release/sonar-results}"
baseline_dir="${3:-output/benchmark/baseline/main}"
mkdir -p "$out_dir"

RELEASED=(
  sonar-koopman/openrouter-_typesafe_jev-latest/spec-10pct/seed-42
  sonar-koopman/openrouter-_typesafe_jev-latest/spec-100pct/seed-42
  sonar-koopman/openrouter-cloudflare_clef/spec-10pct/seed-42
  sonar-koopman/openrouter-cloudflare_clef/spec-100pct/seed-42
  sonar-koopman/openrouter-cloudflare_clef-flash/spec-10pct/seed-42
  sonar-koopman/openrouter-cloudflare_clef-flash/spec-100pct/seed-42
  sonar-koopman/openrouter-perplexity_pplx-decider-v1-27b/spec-10pct/seed-42
  sonar-koopman/openrouter-perplexity_pplx-decider-v1-27b/spec-100pct/seed-42
  sonar-koopman-uniform-prior/openrouter-_typesafe_jev-latest/spec-100pct/seed-42
  sonar-greedy-r1/openrouter-_typesafe_jev-latest/spec-100pct/seed-42
  sonar-koopman-no-assessment/openrouter-_typesafe_jev-latest/spec-100pct/seed-42
  sonar-koopman-no-localization/openrouter-_typesafe_jev-latest/spec-100pct/seed-42
  sonar-koopman/google-gemini-3.8-flash/spec-100pct/seed-42
)

if $with_traces; then
# A cell staged earlier but no longer released is removed.
if [ -d "$out_dir/results" ]; then
  while read -r staged; do
    relative="${staged#"$out_dir"/results/}"
    if ! printf '%s\n' "${RELEASED[@]}" | grep -qxF "$relative"; then
      rm -rf "$staged"
    fi
  done < <(find "$out_dir/results" -mindepth 4 -maxdepth 4 -type d -name 'seed-*')
  find "$out_dir/results" -type d -empty -delete
fi

for relative in "${RELEASED[@]}"; do
  cell="$run_dir/cells/$relative"
  target="$out_dir/results/$relative"
  mkdir -p "$target"
  for file in model provider theta extra-args; do
    if [ -f "$cell/$file" ]; then
      cp "$cell/$file" "$target/$file"
    fi
  done
  for run in "$cell"/runs/*; do
    run_target="$target/runs/$(basename "$run")"
    mkdir -p "$run_target"
    cp "$run/run.json" "$run/events.jsonl" "$run_target/"
    archive="$run_target/details.jsonl.zst"
    if [ -f "$run/details.jsonl" ] && [ ! "$archive" -nt "$run/details.jsonl" ]; then
      echo "compressing $relative/runs/$(basename "$run")/details.jsonl"
      zstd -q -f -12 -T0 --long=27 "$run/details.jsonl" -o "$archive"
    fi
  done
done

# The oldest baseline cells have no seed directory; they are staged as seed-42.
baseline_cells=()
for spec in "$baseline_dir"/cells/*/*/spec-*pct; do
  if compgen -G "$spec/seed-*" > /dev/null; then
    for cell in "$spec"/seed-*; do baseline_cells+=("$cell"); done
  elif [ -f "$spec/scenarios.jsonl" ]; then
    baseline_cells+=("$spec")
  fi
done
for cell in "${baseline_cells[@]}"; do
  relative="${cell#"$baseline_dir"/cells/}"
  case "$relative" in */seed-*) ;; *) relative="$relative/seed-42" ;; esac
  target="$out_dir/results/baselines/$relative"
  mkdir -p "$target"
  cp "$cell/config.yaml" "$cell/scenarios.jsonl" "$cell/llm_calls.jsonl" "$target/"
  if [ ! "$target/tasks.tar.zst" -nt "$cell/scenarios.jsonl" ]; then
    echo "packing baselines/$relative/tasks"
    tar -C "$cell" -cf - tasks | zstd -q -f -12 -T0 -o "$target/tasks.tar.zst"
  fi
done
else
  rm -rf "$out_dir/results"
fi

rm -rf "$out_dir/comparison" "$out_dir/scripts"
mkdir -p "$out_dir/comparison"
python3 - "$run_dir" "$out_dir" "${RELEASED[@]}" <<'PY'
import csv, json, sys
from pathlib import Path
run_dir, out_dir, cells = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3:]
BASELINES = {"monolithic", "react"}
released = set()
for cell in cells:
    method, slug, spec, _ = cell.split("/")
    model = (run_dir / "cells" / cell / "model").read_text().strip()
    released.add((method, model, spec[len("spec-"):-len("pct")]))


def kept(method, model, pct):
    return (method, model, str(pct)) in released or method in BASELINES


def stage(src, dst):
    """Copies the exports in src to dst, keeping only the rows of released results."""
    for name in ("per_scenario.csv", "summary.csv", "summary_by_seed.csv"):
        with open(src / name) as f:
            reader = csv.DictReader(f)
            rows = [r for r in reader if kept(r["method"], r["model"], r["spec_pct"])]
            fields = reader.fieldnames
        with open(dst / name, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
    summary = json.loads((src / "summary.json").read_text())
    for key in ("summary", "summary_by_seed"):
        summary[key] = [r for r in summary[key] if kept(r["method"], r["model"], r["spec_pct"])]
    models = {r["model"] for r in summary["summary"]}
    summary["pricing_usd_per_1m_tokens"] = {
        model: price for model, price in summary["pricing_usd_per_1m_tokens"].items()
        if model in models}
    (dst / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")


stage(run_dir, out_dir)
stage(run_dir / "comparison", out_dir / "comparison")
# paired.csv: one line per scenario with a column group per method; blank unreleased results.
with open(run_dir / "comparison" / "paired.csv") as f:
    reader = csv.DictReader(f)
    fields = reader.fieldnames
    rows = list(reader)
for row in rows:
    for field in fields[3:]:
        method, rest = field.split("[", 1)
        model = rest.split("]", 1)[0]
        if not kept(method, model, row["spec_pct"]):
            row[field] = ""
groups = {f.rsplit(".", 1)[0] for f in fields[3:]}
empty = {g for g in groups if all(not row[f] for row in rows for f in fields[3:]
                                  if f.rsplit(".", 1)[0] == g)}
fields = fields[:3] + [f for f in fields[3:] if f.rsplit(".", 1)[0] not in empty]
with open(out_dir / "comparison" / "paired.csv", "w", newline="") as f:
    writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
    writer.writeheader()
    writer.writerows(rows)
PY
mkdir -p "$out_dir/scripts"
cp LICENSE "$out_dir/LICENSE"
cp scripts/benchmark.sh scripts/benchmark-export.py scripts/benchmark-pricing.json \
  scripts/benchmark-release.sh "$out_dir/scripts/"

(cd "$out_dir" && find . -type f ! -name MANIFEST.sha256 | LC_ALL=C sort \
  | xargs shasum -a 256 > MANIFEST.sha256)
du -sh "$out_dir"
