#!/usr/bin/env bash
# Runs bench_open.sh over the P-010..P-014 books (3 runs each) for the given packages.
# Usage: tools/run_open_matrix.sh "<pkg>:<book>:<wait> ..."
cd "$(dirname "$0")/.."
OUT=results/raw/open
for spec in $1; do
  IFS=: read -r pkg b w <<<"$spec"
  PREWARM=${PREWARM:-true} tools/bench_open.sh "$pkg" "$b" 3 "$w" "$OUT"
done
