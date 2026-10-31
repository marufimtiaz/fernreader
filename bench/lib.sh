# bench/lib.sh — sourced by every bench group
set -u
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
PKG=${PKG:-app.epubreader}
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
