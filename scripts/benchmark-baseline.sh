#!/usr/bin/env bash
# Usage: scripts/benchmark-baseline.sh [host] [run-name]
# Copies the exports of the Cornetto LLM baselines (monolithic, react) from the benchmark host into
# output/benchmark/baseline/<run-name>, for scripts/benchmark-export.py --baseline. Only the CSV and
# JSON exports are copied, not the per-task directories.
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$root"
host="${1:-kim@172.31.212.92}"
run_name="${2:-main}"
target="output/benchmark/baseline/$run_name"
mkdir -p "$target"
for file in per_scenario.csv summary_by_seed.csv summary.csv summary.json; do
  scp -q "$host:cornetto/results_bench/$run_name/$file" "$target/$file"
done
echo "Copied the baseline exports to $target"
