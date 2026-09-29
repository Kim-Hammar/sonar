#!/usr/bin/env bash
# Usage: scripts/check.sh  (lints, tests and builds the web interface, then runs every Gradle check)
set -euo pipefail
. "$(dirname "$0")/common.sh"
"$root/scripts/ui.sh" lint
"$root/scripts/ui.sh" test
"$root/scripts/ui.sh" build
cd "$root"
exec ./gradlew build "$@"
