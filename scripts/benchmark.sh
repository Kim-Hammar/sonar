#!/usr/bin/env bash
# Usage: scripts/benchmark.sh [--run-name NAME] [--policies "greedy koopman"] [--models "MODEL ..."]
#                             [--spec-pcts "10 100"] [--seeds "42 ..."] [--scenarios a,b,...]
#                             [--scenario-parallelism N] [--theta θ] [--dry-run]
#                             [--variant NAME --extra-args "ARGS"] [--provider NAME]
#                             [--install NAME]
#
# Repairs every scenario per (policy, model, specification share, seed) cell and exports the results
# in the format of Cornetto's comparison benchmark. Results go to output/benchmark/<run>, cells to
# cells/sonar-<policy>[-<variant>]/<provider>-<model>/spec-<pct>pct/seed-<seed>.
#
# Finished scenarios are not rerun, and scenarios without specifications at <pct> are skipped. Each
# cell has its own decision cache, so token usage is not reduced by other cells. The build is
# installed into the run directory; a concurrent sweep of the same run needs its own --install.
#
# Ablation: --variant uniform-prior --extra-args "--prior uniform"
# Other provider: --provider google --models gemini-3.8-flash --extra-args "--decision-api chat
#   --endpoint https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
#   --api-key-variable GOOGLE_API_KEY"
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
theta=""
variant=""
extra_args=""
provider="openrouter"
install_name="sonar-install"
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
    --theta) theta="$2"; shift 2 ;;
    --variant) variant="$2"; shift 2 ;;
    --extra-args) extra_args="$2"; shift 2 ;;
    --provider) provider="$2"; shift 2 ;;
    --install) install_name="$2"; shift 2 ;;
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
install="$run_dir/$install_name"
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
  git diff > "$run_dir/git-diff"
fi

for model in $models; do
  model_slug="$provider-$(tr '/~' '__' <<< "$model")"
  for spec_pct in $spec_pcts; do
    for seed in $seeds; do
      for policy in $policies; do
        method="sonar-$policy${variant:+-$variant}"
        cell="$run_dir/cells/$method/$model_slug/spec-${spec_pct}pct/seed-$seed"
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
        echo "[$method | $model | $spec_pct% | seed $seed] ${#pending[@]} to run"
        if [ ${#pending[@]} -eq 0 ]; then
          continue
        fi

        command=(
          "$install/bin/sonar" repair
          --dataset "$dataset"
          --specifications "$spec_pct"
          --policy "$policy"
          ${theta:+--theta "$theta"}
          --seed "$seed"
          --model "$model"
          --verification-workers 4
          --decision-concurrency 4
          --generation-parallelism 8
          --scenario-parallelism "$scenario_parallelism"
          --max-decisions 60
          --max-verifications 300
          --name "bench-$run_name-$policy${variant:+-$variant}-$spec_pct-$seed"
          --output "$cell"
          --scenario "$(IFS=,; echo "${pending[*]}")"
        )
        read -r -a extra <<< "$extra_args"
        command+=(${extra[@]+"${extra[@]}"})
        if $dry_run; then
          printf "%q " "${command[@]}" | cut -c1-600
          echo
          continue
        fi
        mkdir -p "$cell"
        echo "$model" > "$cell/model"
        echo "${theta:-default}" > "$cell/theta"
        echo "$extra_args" > "$cell/extra-args"
        echo "$provider" > "$cell/provider"
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
