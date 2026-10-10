#!/usr/bin/env bash
# Usage: scripts/benchmark-release.sh [RUN_DIR] [OUT_DIR]
#
# Stages the results of a benchmark run (scripts/benchmark.sh) for publication as a dataset,
# without uploading anything. RUN_DIR defaults to output/benchmark/main and OUT_DIR to
# output/release/sonar-results.
#
# Layout of OUT_DIR:
#   results/<method>/<provider>-<model>/spec-<pct>pct/seed-<seed>/
#       model, provider, theta, extra-args          the cell's settings
#       runs/<id>/run.json, events.jsonl            the run's settings and trace
#       runs/<id>/details.jsonl.zst                 the detailed trace, compressed with zstd
#   per_scenario.csv, summary*.csv, summary.json    sonar's results in Cornetto's format
#   comparison/                                     the same merged with the LLM baselines
#   plots/                                          the figures (pdf, png, tex)
#   provenance/                                     the commit and diff the runs were built from
#   scripts/                                        the scripts that ran, exported and plotted
#   MANIFEST.sha256                                 a checksum of every file
#
# Only the cells in RELEASED are staged, and the result files keep only their rows: SONAR with
# each decision model (the comparison with the baselines, 10 % and 100 %) and the ablations
# (100 %). The LLM baselines are added once their results are imported.
#
# Left out: the sonar installs, Batfish work directories, decision caches (their replies are in
# the details) and plot caches. Rerunning the script updates OUT_DIR in place: a details file is
# compressed again only when it is newer than its archive.
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$root"

run_dir="${1:-output/benchmark/main}"
out_dir="${2:-output/release/sonar-results}"
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

python3 - "$run_dir" "$out_dir" "${RELEASED[@]}" <<'PY'
import csv, json, sys
from pathlib import Path
run_dir, out_dir, cells = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3:]
released = set()
for cell in cells:
    method, slug, spec, _ = cell.split("/")
    model = (run_dir / "cells" / cell / "model").read_text().strip()
    released.add((method, model, spec[len("spec-"):-len("pct")]))
for name in ("per_scenario.csv", "summary.csv", "summary_by_seed.csv"):
    with open(run_dir / name) as f:
        reader = csv.DictReader(f)
        rows = [r for r in reader if (r["method"], r["model"], r["spec_pct"]) in released]
        fields = reader.fieldnames
    with open(out_dir / name, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
summary = json.loads((run_dir / "summary.json").read_text())
for key in ("summary", "summary_by_seed"):
    summary[key] = [r for r in summary[key]
                    if (r["method"], r["model"], str(r["spec_pct"])) in released]
models = {model for _, model, _ in released}
summary["pricing_usd_per_1m_tokens"] = {
    model: price for model, price in summary["pricing_usd_per_1m_tokens"].items()
    if model in models}
(out_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
PY
rm -rf "$out_dir/comparison" "$out_dir/plots" "$out_dir/provenance" "$out_dir/scripts"
mkdir -p "$out_dir/plots" "$out_dir/provenance" "$out_dir/scripts"
cp "$run_dir"/plots/*.pdf "$run_dir"/plots/*.png "$run_dir"/plots/*.tex "$out_dir/plots/"
cp "$run_dir"/git-commit* "$run_dir"/git-diff* "$out_dir/provenance/"
cp LICENSE "$out_dir/LICENSE"
cp scripts/benchmark.sh scripts/benchmark-export.py scripts/benchmark-plots.py \
  scripts/benchmark-pricing.json scripts/benchmark-release.sh "$out_dir/scripts/"

(cd "$out_dir" && find . -type f ! -name MANIFEST.sha256 | LC_ALL=C sort \
  | xargs shasum -a 256 > MANIFEST.sha256)
du -sh "$out_dir"
