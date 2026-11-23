#!/usr/bin/env bash
# bench/scan_noise.sh — C-075: manual rescan vs the incremental scan on return, with a realistic shared storage
# (many non-book files). `add` creates /sdcard/ScanNoise with $DIRS x $DIRS folders of $FILES small files each
# (default 10 x 10 x 50 = 5,000 files in 110 nested folders); `measure` times $RUNS manual rescans (bench `rescan`)
# and $RUNS scans on return (Home, then am start), from the app's scan_complete events; `remove` deletes the folder.
# Needs the benchmark build. Usage: bench/scan_noise.sh [-s serial] add|measure|remove ...
set -u
[ "${1:-}" = -s ] && { export ANDROID_SERIAL=$2; shift 2; }
A=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}
PKG=${PKG:-app.fernreader}; ACT=${ACT:-app.fernreader.MainActivity}
NOISE=/sdcard/ScanNoise; DIRS=${DIRS:-10}; FILES=${FILES:-50}; RUNS=${RUNS:-5}
bench_cmd() { $A shell am broadcast -a app.fernreader.BENCH -n "$PKG/app.fernreader.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
wait_complete() { # prints the ms= of the first scan_complete after the logcat was cleared
  for _ in $(seq 1 300); do
    local v; v=$($A logcat -d -s EPR:I | awk '/EVT scan_complete /{ match($0,/ms=[0-9]+/); print substr($0,RSTART+3,RLENGTH-3); exit }')
    [ -n "$v" ] && { echo "$v"; return; }; sleep 0.1
  done; echo NA
}
median() { sort -n | awk '{a[NR]=$1} END { if (NR) print a[int((NR+1)/2)]; else print "NA" }'; }
for step in "$@"; do case $step in
  add)
    $A shell "for a in \$(seq 1 $DIRS); do for b in \$(seq 1 $DIRS); do d=$NOISE/a\$a/b\$b; mkdir -p \$d; for f in \$(seq 1 $FILES); do echo noise > \$d/f\$f.jpg; done; done; done"
    echo "noise: $($A shell find $NOISE -type f | wc -l | tr -d ' \r') files in $NOISE" ;;
  measure)
    $A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 5
    man=(); ret=()
    for i in $(seq 1 "$RUNS"); do
      $A logcat -c; bench_cmd rescan; man+=("$(wait_complete)"); sleep 3
      $A shell input keyevent KEYCODE_HOME; sleep 3
      $A logcat -c; $A shell am start -W -n "$PKG/$ACT" >/dev/null; ret+=("$(wait_complete)"); sleep 3
    done
    files=$($A logcat -d -s EPR:I | grep -o 'files=[0-9]*' | tail -1)
    echo "manual rescan ms: ${man[*]}; median $(printf '%s\n' "${man[@]}" | median)"
    echo "scan on return ms: ${ret[*]}; median $(printf '%s\n' "${ret[@]}" | median) ($files)" ;;
  remove) $A shell rm -rf "$NOISE"; echo "removed $NOISE" ;;
  *) echo "unknown step: $step" >&2; exit 2 ;;
esac; done
