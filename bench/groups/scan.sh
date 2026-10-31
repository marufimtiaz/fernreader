#!/usr/bin/env bash
# bench/groups/scan.sh — P-050 first full scan (and first book visible), P-051 unchanged rescan, P-052 rescan after
# adding 14 small books. Timings are the app's own events (logcat tag EPR): scan_started -> scan_complete and
# scan_started -> library_first_book (the first non-empty library composed). Corpus + folder set must be on storage
# (bench/push_corpus.sh). Needs the benchmark build (Probe events, BENCH receiver). 3 runs.
source "$(dirname "$0")/../lib.sh"
BATCH=/sdcard/Download/Batch14
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
python3 -I "$SPEC_TOOLS/gen_folder_books.py" "$TMP" b01 b02 b03 b04 b05 b06 b07 b08 b09 b10 b11 b12 b13 b14 >/dev/null
wait_ev() { # <event> <seconds>: wait until the event is in logcat
  for _ in $(seq 1 $(( $2 * 10 ))); do $A logcat -d -s EPR:I | grep -q "EVT $1 " && return 0; sleep 0.1; done; return 1
}
complete_ms() { $A logcat -d -s EPR:I | awk '/EVT scan_complete /{ match($0,/ms=[0-9]+/); v=substr($0,RSTART+3,RLENGTH-3) } END{ print v }'; }
$A shell rm -rf "$BATCH"
for RUN in 1 2 3; do
  $A shell pm clear "$PKG" >/dev/null
  $A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
  $A logcat -c
  $A shell am start -W -n "$PKG/$ACT" >/dev/null
  wait_ev scan_complete 30 || echo "WARN: no scan_complete (run $RUN)" >&2
  wait_ev library_first_book 5 || true
  files=$($A logcat -d -s EPR:I | awk '/EVT scan_complete /{ match($0,/files=[0-9]+/); print substr($0,RSTART+6,RLENGTH-6) }' | tail -1)
  emit P-050 "$(ev_delta scan_started scan_complete)" "files=$files"
  emit P-050.first "$(ev_delta scan_started library_first_book)"
  sleep 3 # covers are filled after the scan; let them finish before timing rescans
  $A logcat -c; bench_cmd rescan; wait_ev scan_complete 10
  emit P-051 "$(complete_ms)"
  $A shell mkdir -p "$BATCH"; $A push "$TMP"/*.epub "$BATCH/" >/dev/null
  $A logcat -c; bench_cmd rescan; wait_ev scan_complete 10
  emit P-052 "$(complete_ms)" "$($A logcat -d -s EPR:I | grep -o 'added=[0-9]*' | tail -1)"
  $A shell rm -rf "$BATCH"
done
