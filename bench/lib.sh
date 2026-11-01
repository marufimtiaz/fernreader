# bench/lib.sh — sourced by every bench group
set -u
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
PKG=${PKG:-app.fernreader}
ACT=${ACT:-app.epubreader.MainActivity}
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SPEC_TOOLS=$ROOT/tools/spec-tools
RESULTS=${RESULTS:?run through bench/run_all.sh (sets RESULTS)}
RELEASE=${BENCH_RELEASE:-0}

require_ad() { : "${AD:?export AD = the agent-device command returned by the T3 device_open tool (host local, device Medium_Phone)}"; }
# emit id value [note] — a non-numeric value is written as 999999 (fails the Must) with the raw value in the note,
# so the results file always stays valid JSON for gate.py.
emit() {
  local id=$1 val=$2 note=${3:-}
  if ! [[ $val =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
    note="raw=${val}${note:+ $note}"; val=999999
  fi
  note=${note//\\/\\\\}; note=${note//\"/\\\"}
  printf '{"id":"%s","value":%s,"run":%s,"note":"%s"}\n' "$id" "$val" "${RUN:-0}" "$note" >> "$RESULTS"
  echo "  $id = $val $note"
}
# ev_delta <from-event> <to-event>: ms between the first <from> and the first following <to> in logcat (tag EPR)
ev_delta() {
  $A logcat -d -s EPR:I | awk -v a="EVT $1 " -v b="EVT $2 " '
    function t() { match($0, /t=[0-9]+/); return substr($0, RSTART+2, RLENGTH-2) }
    index($0, a) && s=="" { s=t() } index($0, b) && s!="" && e=="" { e=t() }
    END { if (s!="" && e!="") print e-s; else print "NA" }'
}
# wait_ev <event> [timeout_s]: waits until logcat (tag EPR) holds the event; returns 1 on timeout.
wait_ev() {
  local end=$(( $(date +%s) + ${2:-20} ))
  until $A logcat -d -s EPR:I | grep -q "EVT $1 "; do
    [ "$(date +%s)" -ge "$end" ] && return 1
    sleep 0.3
  done
}
bench_cmd() { $A shell am broadcast -a app.fernreader.BENCH -n "$PKG/app.epubreader.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
video_ms() { bash "$SPEC_TOOLS/summarize_open.sh" "$1" | sed 's/.*open_ms=//'; }
# video_first_ms <video>: ms from the first visible change (the bench command's marker) to the first large change after it
# (mean-abs-diff >= 2 over >= 2 % of the pixels: the page starts to move, not a label). NA when the first change is already large (no marker seen). P-026 (C-071): input -> first frame of the boundary slide.
video_first_ms() {
  python3 -I "$SPEC_TOOLS/frame_changes.py" "$1" 0.5 | awk '
    /^#/ {next}
    { t=$1; match($0, /mad= *[0-9.]+/); m=substr($0, RSTART+4, RLENGTH-4)+0; match($0, /changed=[0-9.]+/); c=substr($0, RSTART+8, RLENGTH-8)+0
      if (t < 1.3) next # the 1.5 s pre-roll before the command: a status-bar tick there is not the marker
      if (t0=="") { if (m < 2) { t0=t; next } else { nomark=1; exit } } if (m>=2 && c>=0.02 && t1=="") t1=t }
    END { if (nomark || t0=="" || t1=="") print "NA"; else printf "%d\n", (t1-t0)*1000 }'
}
B=/sdcard/Books
WP=$B/gutenberg/war-and-peace-epub3.epub
