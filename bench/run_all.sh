#!/usr/bin/env bash
# bench/run_all.sh <group...|all> [--release] — runs bench/groups/<group>.sh, then the gate (Must only).
# Run at the end of each milestone (all groups built so far) and before release (all --release). Not per build.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
GRPS=(); export BENCH_RELEASE=0
for g in "$@"; do
  case "$g" in
    --release) BENCH_RELEASE=1 ;;
    all) for f in bench/groups/*.sh; do GRPS+=("$(basename "$f" .sh)"); done ;;
    *) GRPS+=("$g") ;;
  esac
done
mkdir -p bench/results
export RESULTS="$ROOT/bench/results/$(date -u +%Y%m%dT%H%M%SZ)-$(git rev-parse --short HEAD).jsonl"
: > "$RESULTS"
[ ${#GRPS[@]} -gt 0 ] || { echo "usage: bench/run_all.sh <group...|all> [--release]" >&2; exit 2; }
empty=0
for g in "${GRPS[@]}"; do
  echo "== $g"
  before=$(wc -l < "$RESULTS")
  bash "bench/groups/$g.sh" || echo "WARN: $g exited non-zero" >&2
  if [ "$(wc -l < "$RESULTS")" -eq "$before" ]; then echo "FAIL: group '$g' produced no results" >&2; empty=1; fi
done
python3 -I bench/gate.py "$RESULTS" bench/targets.json; rc=$?
[ "$empty" -eq 0 ] || rc=1
echo "results: $RESULTS"; exit $rc
