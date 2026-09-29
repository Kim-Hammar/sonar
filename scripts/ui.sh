#!/usr/bin/env bash
# Usage: scripts/ui.sh install|build|dev|test|lint|format  (runs the web interface's npm tasks in sonar-web/frontend)
set -euo pipefail
. "$(dirname "$0")/common.sh"
if ! command -v npm >/dev/null 2>&1; then
  echo "the web interface needs Node.js 22.12 or newer" >&2
  exit 1
fi
cd "$root/sonar-web/frontend"
task="${1:-build}"
if [ "$task" = install ] || [ ! -d node_modules ] || [ package-lock.json -nt node_modules ]; then
  npm ci --no-audit --no-fund
  touch node_modules
fi
case "$task" in
  install) ;;
  build) npm run build ;;
  dev) exec npm run dev ;;
  test) npm test ;;
  lint) npm run lint && npm run format:check ;;
  format) npm run format ;;
  *)
    echo "usage: scripts/ui.sh install|build|dev|test|lint|format" >&2
    exit 2
    ;;
esac
