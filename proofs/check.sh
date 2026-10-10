#!/usr/bin/env bash
# Usage: proofs/check.sh
#
# Checks the formalization: builds it with Mathlib's prebuilt cache, fails if a source uses
# `sorry`, `admit` or declares an `axiom`, and prints the axioms that each theorem listed in
# Audit.lean depends on, failing if any is not one of Lean's standard axioms (propext,
# Classical.choice, Quot.sound).
set -euo pipefail
cd "$(dirname "$0")"

lake exe cache get > /dev/null
lake build

if grep -rnE '\b(sorry|admit)\b|^\s*axiom\b' Sonar Sonar.lean; then
  echo "error: the sources above use sorry or admit, or declare an axiom" >&2
  exit 1
fi

report="$(lake env lean Audit.lean)"
echo "$report"
if grep -oE "depends on axioms: \[[^]]*\]" <<< "$report" \
    | grep -oE '[A-Za-z_.]+' | grep -vxE 'depends|on|axioms|propext|Classical\.choice|Quot\.sound' \
    | grep -q .; then
  echo "error: a theorem depends on a non-standard axiom" >&2
  exit 1
fi
echo "ok: every proof checks without sorry and uses only the standard axioms"
