#!/usr/bin/env bash
# Usage: scripts/theta-experiment.sh [scenario,scenario,...]
# Repairs the same scenarios once per decision model, each into output/theta-<model>, and then
# estimates every model's θ into output/theta-figure. Without arguments it takes every eighth
# scenario (29 in all). A model is run only on the scenarios its earlier runs did not finish.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -gt 0 ]; then
  scenarios="$1"
else
  scenarios="$(ls dataset/main_dataset | grep -v -e scenario-094 -e scenario-095 \
    | awk 'NR % 8 == 1' | paste -sd, -)"
fi

models=(
  "Jev 1.13=typesafe/jev-1.13"
  "Decider V1=perplexity/pplx-decider-v1-27b"
  "Clef=cloudflare/clef"
  "Clef Flash=cloudflare/clef-flash"
)

theta_args=()
for entry in "${models[@]}"; do
  name="${entry%%=*}"
  model="${entry#*=}"
  output="output/theta-${model##*/}"
  finished="$(cat "$output"/runs/*/events.jsonl 2> /dev/null \
    | grep '"type":"finished"' | grep -o '"scenario":"[^"]*"' | cut -d'"' -f4 || true)"
  missing="$(tr , '\n' <<< "$scenarios" | grep -vxF -e "${finished:-none}" | paste -sd, - || true)"
  if [ -n "$missing" ]; then
    scripts/run.sh repair --specifications 100 --verification-workers 4 --decision-concurrency 4 \
      --generation-parallelism 8 --scenario-parallelism 2 --max-decisions 60 \
      --max-verifications 300 --model "$model" --name "theta-${model##*/}" --output "$output" \
      --scenario "$missing"
  fi
  theta_args+=(--model-runs "$name=$output/runs")
done
scripts/run.sh theta "${theta_args[@]}" --output output/theta-figure
