#!/usr/bin/env bash
# Usage: scripts/fetch-batfish.sh  (copies the pinned Batfish jar out of its Docker image into third_party/batfish)
set -euo pipefail
. "$(dirname "$0")/common.sh"
property() { sed -n "s/^$1=//p" "$root/gradle.properties"; }
digest() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
jar="$root/$(property sonar.batfish.jar)"
expected="$(property sonar.batfish.sha256)"
image="$(property sonar.batfish.image)"
if [ -f "$jar" ] && [ "$(digest "$jar")" = "$expected" ]; then
  echo "Batfish jar already present: $jar"
  exit 0
fi
mkdir -p "$(dirname "$jar")"
container="$(docker create --platform linux/amd64 "$image")"
trap 'docker rm -f "$container" >/dev/null' EXIT
docker cp "$container:/allinone-bundle.jar" "$jar.part"
actual="$(digest "$jar.part")"
if [ "$actual" != "$expected" ]; then
  rm -f "$jar.part"
  echo "Batfish jar has SHA-256 $actual but $expected is pinned" >&2
  exit 1
fi
mv "$jar.part" "$jar"
echo "Batfish jar written to $jar"
