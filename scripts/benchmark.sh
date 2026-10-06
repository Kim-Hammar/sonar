#!/usr/bin/env bash
# Usage: scripts/benchmark.sh [--run-name NAME] [--policies "greedy koopman"] [--models "MODEL ..."]
#                             [--spec-pcts "10 100"] [--seeds "42 ..."] [--scenarios a,b,...]
#                             [--scenario-parallelism N] [--dry-run]
#
# The sonar counterpart of the Cornetto comparison benchmark (src/benchmark/compare.py in the
# cornetto repository): repairs every scenario once per (policy, model, specification share, seed)
# cell and exports the results in Cornetto's format, so that they can be put next to the LLM
# baselines (monolithic, react).
#
# Layout, mirroring Cornetto's results_bench/<run>:
#   output/benchmark/<run>/cells/sonar-<policy>/<provider>-<model>/spec-<pct>pct/seed-<seed>/
#       runs/<id>/events.jsonl, decision-cache, batfish   (the sonar output directory of the cell)
#   output/benchmark/<run>/per_scenario.csv, summary_by_seed.csv, summary.csv, summary.json
#
# Every cell has its own output directory and hence an empty decision cache, so the recorded token
# usage is what the decision model was really asked. A cell is resumable: a scenario with a
# finished event is not repaired again. Scenarios without specifications_<pct>pct.csv (94 and 95
# at 100 %) are skipped and exported as missing_spec, as Cornetto does.
#
# The sonar build is installed once into output/benchmark/<run>/sonar-install and run from there,
# so rebuilding the project does not replace the jars under a running benchmark.
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$root"

run_name="main"
policies="greedy"
models="~typesafe/jev-latest"
spec_pcts="10 100"
seeds="42"
scenarios=""
scenario_parallelism=1
dry_run=false
while [ $# -gt 0 ]; do
  case "$1" in
    --run-name) run_name="$2"; shift 2 ;;
    --policies) policies="$2"; shift 2 ;;
    --models) models="$2"; shift 2 ;;
    --spec-pcts) spec_pcts="$2"; shift 2 ;;
    --seeds) seeds="$2"; shift 2 ;;
    --scenarios) scenarios="$2"; shift 2 ;;
    --scenario-parallelism) scenario_parallelism="$2"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi

dataset="dataset/main_dataset"
run_dir="output/benchmark/$run_name"
install="$run_dir/sonar-install"
if [ -z "$scenarios" ]; then
  scenarios="$(ls "$dataset" | grep '^scenario-' | paste -sd, -)"
fi

if ! $dry_run; then
  mkdir -p "$run_dir"
  java_home="$(./gradlew -q --console=plain :sonar-cli:installDist :sonar-cli:printJavaHome | tail -n 1)"
  rm -rf "$install"
  cp -R sonar-cli/build/install/sonar "$install"
  echo "$java_home" > "$run_dir/java-home"
  git rev-parse HEAD > "$run_dir/git-commit"
  git status --short > "$run_dir/git-status"
fi

for model in $models; do
  model_slug="openrouter-$(tr '/~' '__' <<< "$model")"
  for spec_pct in $spec_pcts; do
    for seed in $seeds; do
      for policy in $policies; do
        cell="$run_dir/cells/sonar-$policy/$model_slug/spec-${spec_pct}pct/seed-$seed"
        finished="$(cat "$cell"/runs/*/events.jsonl 2> /dev/null \
          | grep '"type":"finished"' | grep -o '"scenario":"[^"]*"' | cut -d'"' -f4 || true)"
        pending=()
        for scenario in $(tr , ' ' <<< "$scenarios"); do
          if [ ! -f "$dataset/$scenario/specifications/specifications_${spec_pct}pct.csv" ]; then
            continue
          fi
          if grep -qxF "$scenario" <<< "$finished"; then
            continue
          fi
          pending+=("$scenario")
        done
        echo "[sonar-$policy | $model | $spec_pct% | seed $seed] ${#pending[@]} to run"
        if [ ${#pending[@]} -eq 0 ]; then
          continue
        fi

        command=(
          "$install/bin/sonar" repair
          --dataset "$dataset"
          --specifications "$spec_pct"
          --policy "$policy"
          --seed "$seed"
          --model "$model"
          --verification-workers 4
          --decision-concurrency 4
          --generation-parallelism 8
          --scenario-parallelism "$scenario_parallelism"
          --max-decisions 60
          --max-verifications 300
          --name "bench-$run_name-$policy-$spec_pct-$seed"
          --output "$cell"
          --scenario "$(IFS=,; echo "${pending[*]}")"
        )
        if $dry_run; then
          printf '%q ' "${command[@]}" | cut -c1-400
          echo
          continue
        fi
        mkdir -p "$cell"
        echo "$model" > "$cell/model"
        # A scenario that is not solved is a result, not a reason to stop the sweep
        JAVA_HOME="$(cat "$run_dir/java-home")" "${command[@]}" 2>&1 | tee -a "$cell/console.log" || true
        python3 scripts/benchmark-export.py "$run_dir"
      done
    done
  done
done

if ! $dry_run; then
  python3 scripts/benchmark-export.py "$run_dir"
fi
