#!/usr/bin/env bash
# Usage: scripts/web.sh [--dataset DIR] [--output DIR] [--port N] [--demo]  (builds the web interface and serves it with sonar serve)
set -euo pipefail
. "$(dirname "$0")/common.sh"
"$root/scripts/ui.sh" build
exec "$root/scripts/run.sh" serve "$@"
