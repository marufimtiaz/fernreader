#!/usr/bin/env bash
# Times one in-reader action with a screen recording plus logged events.
# The reader toggles a black marker square on every debug command, so the first visible change in the
# recording is the command (stands in for the tap ripple); end = last change with mean-abs-diff >= 2.
# Usage: tools/bench_action.sh <pkg> <tag> <wait s> <cmd> [arg]
# Prints: tag video_ms log_ms (cmd -> last paint event within the wait window)
set -u
PKG=$1; TAG=$2; WAIT=$3; CMD=$4; ARG=${5:-}
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
AD=${AD:?set AD}
SPEC=${SPEC:-$HOME/Projects/epub-reader-spec}
OUT=${OUT:-results/raw/action}; mkdir -p "$OUT"
v="$OUT/${PKG##*.}-$TAG.mp4"
$AD record start "$v" --scope device --hide-touches >/dev/null
sleep 1.5
$A logcat -c
$A shell am broadcast -a "$PKG.CMD" --es cmd "$CMD" --es arg "'$ARG'" >/dev/null
sleep "$WAIT"
$AD record stop >/dev/null
vid=$(bash "$SPEC/tools/summarize_open.sh" "$v" | sed 's/.*open_ms=//')
lg=$($A logcat -d -s PROTO:I | awk '
  /EVT cmd / { match($0, /t=[0-9]+/); t0=substr($0, RSTART+2, RLENGTH-2) }
  /EVT (painted_js|painted) / && t0!="" { match($0, /t=[0-9]+/); tp=substr($0, RSTART+2, RLENGTH-2) }
  END { if (tp!="") print tp-t0; else print "NA" }')
echo "${PKG##*.} $TAG video_ms=$vid log_ms=$lg video=$v"
