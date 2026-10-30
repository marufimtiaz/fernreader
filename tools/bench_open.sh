#!/usr/bin/env bash
# Open-time benchmark per performance.md: library visible (warm process) -> tap book -> first page painted.
# Each run: fresh process + cleared positions, record screen (touches hidden), tap, wait; then Back and
# re-open the same book (P-016) with a second recording.
# Usage: tools/bench_open.sh <pkg> <book file name> <runs> <wait seconds> <outdir>
# Output: one line per open: kind run video-based open_ms and log-based (tap_down -> first paint event) ms.
set -u
PKG=$1; BOOK=$2; RUNS=$3; WAIT=$4; OUT=$5
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
AD=${AD:?set AD to the agent-device command}
SPEC=${SPEC:-$HOME/Projects/epub-reader-spec}
mkdir -p "$OUT"
# first paint event: foliate reports painted_js from requestAnimationFrame; Readium logs painted after
# the navigator's first onPageChanged (two Choreographer frames).
logms() {
  $A logcat -d -s PROTO:I | awk '
    /EVT tap_down/ { match($0, /t=[0-9]+/); t0=substr($0, RSTART+2, RLENGTH-2); tp=""; err="" }
    /EVT open_error/ { err="ERROR" }
    /EVT (painted_js|painted) / && t0!="" && tp=="" { match($0, /t=[0-9]+/); tp=substr($0, RSTART+2, RLENGTH-2) }
    END { if (err!="") print err; else if (tp!="") print tp-t0; else print "NA" }'
}
one() { # kind run
  local v="$OUT/${PKG##*.}-${BOOK%.epub}-$1-$2.mp4"
  rm -f "$v"
  $AD record stop >/dev/null 2>&1   # clear a recording left over from a failed stop
  $A logcat -c
  $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1 || echo "WARN record start failed" >&2
  sleep 1.5
  $AD press 540 223 >/dev/null
  sleep "$WAIT"
  $AD record stop >/dev/null 2>&1 || echo "WARN record stop failed" >&2
  local vid=NA
  [ -s "$v" ] && vid=$("$(dirname "$0")/summarize_open_fixed.sh" "$v" | sed 's/.*open_ms=//')
  echo "$PKG $BOOK $1 run=$2 video_ms=$vid log_ms=$(logms)"
}
for r in $(seq 1 "$RUNS"); do
  $A shell am force-stop "$PKG"
  $A shell am start -W -n "$PKG/proto.common.LibraryActivity" --es only "$BOOK" --ez reset true --ez prewarm "${PREWARM:-false}" >/dev/null
  sleep 3
  one first "$r"
  $A shell am broadcast -a "$PKG.CMD" --es cmd back >/dev/null
  sleep 3
  one reopen "$r"
done
