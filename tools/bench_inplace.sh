#!/usr/bin/env bash
# Like bench_open.sh, but the book list is shown inside the reader activity with the engine prepared
# behind it ("in-place" mode), so the measured time excludes an activity start/transition.
# Each run: fresh process -> first open; then Back, list again in the same process -> reopen (P-016).
# Usage: tools/bench_inplace.sh <pkg> <book path> <runs> <wait s> <outdir>
set -u
PKG=$1; BOOK=$2; RUNS=$3; WAIT=$4; OUT=$5
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
AD=${AD:?set AD}
T=$(dirname "$0"); mkdir -p "$OUT"
N=$(basename "$BOOK" .epub)
logms() {
  $A logcat -d -s PROTO:I | awk '
    /EVT tap_down/ { match($0, /t=[0-9]+/); t0=substr($0, RSTART+2, RLENGTH-2) }
    /EVT open_error/ { err=1 }
    /EVT (painted_js|painted) / && t0!="" && tp=="" { match($0, /t=[0-9]+/); tp=substr($0, RSTART+2, RLENGTH-2) }
    END { if (err) print "ERROR"; else if (tp!="") print tp-t0; else print "NA" }'
}
one() {
  local v="$OUT/${PKG##*.}-$N-$1-$2.mp4"; rm -f "$v"
  $A shell am start -W -n "$PKG/.ReaderActivity" --esa inplace "$BOOK" --ez chunk "${CHUNK:-false}" >/dev/null
  sleep 4
  $AD record stop >/dev/null 2>&1
  $A logcat -c
  $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1 || echo "WARN record start failed" >&2
  sleep 1.5
  $AD press 540 223 >/dev/null
  sleep "$WAIT"
  $AD record stop >/dev/null 2>&1 || echo "WARN record stop failed" >&2
  local s=NA; [ -s "$v" ] && s=$("$T/split_open.sh" "$v" | cut -d' ' -f2-)
  echo "${PKG##*.} $N inplace-$1 run=$2 $s log_ms=$(logms)"
  $A shell am broadcast -a "$PKG.CMD" --es cmd back >/dev/null; sleep 2
}
for r in $(seq 1 "$RUNS"); do
  $A shell am force-stop "$PKG"
  one first "$r"
  one reopen "$r"
done
