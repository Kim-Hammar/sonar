#!/usr/bin/env bash
# Usage: scripts/run.sh [sonar arguments]  (builds the sonar command and runs it on the project's Java toolchain)
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$root"
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi
java_home="$(./gradlew -q --console=plain :sonar-cli:installDist :sonar-cli:printJavaHome | tail -n 1)"
JAVA_HOME="$java_home" exec sonar-cli/build/install/sonar/bin/sonar "$@"
