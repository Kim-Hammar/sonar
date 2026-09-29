#!/usr/bin/env bash
# Usage: scripts/test.sh [module]  (runs the unit tests of every module, or of one module such as sonar-core)
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$root"
if [ $# -gt 0 ]; then
  exec ./gradlew ":$1:test"
fi
exec ./gradlew test
