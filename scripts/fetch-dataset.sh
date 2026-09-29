#!/usr/bin/env bash
# Usage: scripts/fetch-dataset.sh [DIR]  (downloads the pinned Cornetto benchmark into DIR, dataset/main_dataset by default)
set -euo pipefail
. "$(dirname "$0")/common.sh"
repository=https://huggingface.co/datasets/iprotogeros/cornetto-benchmark
revision=cdf3d68ecc47b4afe63e6b3ca8f5c07821c191bd
destination="${1:-$root/dataset/main_dataset}"
if ! command -v git >/dev/null 2>&1; then
  echo "fetching the dataset needs git" >&2
  exit 1
fi
if [ -f "$destination/.revision" ] && [ "$(cat "$destination/.revision")" = "$revision" ]; then
  echo "Dataset already present: $destination"
  exit 0
fi
if [ -n "$(ls -A "$destination" 2>/dev/null)" ]; then
  echo "$destination is not empty and does not hold revision $revision; remove it or pass another directory" >&2
  exit 1
fi
parent="$(dirname "$destination")"
mkdir -p "$parent"
work="$(mktemp -d "$parent/.fetch.XXXXXX")"
trap 'rm -rf "$work"' EXIT
git -C "$work" init -q
git -C "$work" remote add origin "$repository"
git -C "$work" sparse-checkout set --cone main_dataset
echo "Fetching the Cornetto benchmark at revision ${revision:0:12} from Hugging Face"
git -C "$work" fetch -q --depth=1 origin "$revision"
git -C "$work" checkout -q FETCH_HEAD
echo "$revision" > "$work/main_dataset/.revision"
rmdir "$destination" 2>/dev/null || true
mv "$work/main_dataset" "$destination"
echo "Dataset written to $destination ($(find "$destination" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ') scenarios)"
