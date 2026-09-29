#!/usr/bin/env bash
# Usage: scripts/format.sh  (formats every Java source with google-java-format)
set -euo pipefail
. "$(dirname "$0")/common.sh"
cd "$root"
exec ./gradlew spotlessApply
