#!/usr/bin/env bash
# Opens a book in the app (benchmark build: BenchCommandReceiver), runs bench commands, then saves a screenshot
# (shrunk to 540 px wide) and the case's warnings/errors from logcat.
# Usage: tools/shot.sh <book path on device> <out.png> [wait s] [cmd[=arg] ...]
#   e.g. tools/shot.sh /sdcard/Books/x.epub /tmp/x.png 6 next next "tocJump=Chapter 2"
# Needs: ./gradlew :app:assemblePlayBenchmark installed (same package app.epubreader), all-files access granted.
set -u
BOOK=$1; PNG=$2; WAIT=${3:-6}; shift 3 || shift $#
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
PKG=app.epubreader
cmd() { $A shell am broadcast -a app.epubreader.BENCH -n "$PKG/.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
$A shell am force-stop $PKG
# every case starts fresh: no resumed position or settings from the case before
$A shell pm clear $PKG >/dev/null
$A shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
$A logcat -c
$A shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 2.5
cmd openPath "$BOOK"; n=1
sleep "$WAIT"
for c in "$@"; do
  a=""; [[ "$c" == *=* ]] && a=${c#*=}
  cmd "${c%%=*}" "$a"; n=$((n + 1))
  sleep "${STEP:-1.5}"
done
# every bench command toggles the benchmark build's 24 dp start marker: an even count leaves it off
if (( n % 2 )); then cmd noop; sleep 0.6; fi
$A exec-out screencap -p > "$PNG"
python3 -I - "$PNG" <<'PY' 2>/dev/null || true
import sys
from PIL import Image
p = sys.argv[1]
im = Image.open(p).convert('RGB')
w, h = im.size
if w > 540: im = im.resize((540, round(h * 540 / w)), Image.LANCZOS)
im.save(p, optimize=True)
PY
# warnings and errors only (the EPR probe's I-level events are timings)
$A logcat -d -s EPR:W EPRJS:W AndroidRuntime:E chromium:W | grep -v '^-' > "${PNG%.png}.log"
grep -cE " [EF] " "${PNG%.png}.log" | sed 's/^/errors=/'
