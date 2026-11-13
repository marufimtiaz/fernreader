#!/bin/bash
# Power scenarios (a)-(d) of docs/power-audit.md, shared by check.sh (emulator, gated) and energy.sh (phone, report
# only). Sourced. The caller defines the hooks sc_begin <name> and sc_end <name> (and optionally sc_probe) and sets
# A (the adb command). Needs the benchmark build (bench command receiver, EPR events) installed and set up.
PKG=app.fernreader
BOOK=${BOOK:-/sdcard/Books/gutenberg/moby-dick-epub2-noimages.epub}
TURNS=20                       # scenario b; tools/power/check.py divides by it

bench_cmd() { $A shell am broadcast -a app.fernreader.BENCH -n "$PKG/app.epubreader.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
# A forward swipe across the middle of the screen (tap zones are off by default), scaled to the screen size.
swipe_xy() { $A shell wm size | awk -F'[ x]' '/size/ {w=$(NF-1); h=$NF} END {printf "%d %d %d %d", w*0.83, h*0.54, w*0.14, h*0.54}'; }
swipe() { $A shell input swipe $SWIPE 120; }
wait_ev() { # wait_ev <event> [seconds]: until "EVT <event> " is in logcat
  local end=$(( $(date +%s) + ${2:-60} ))
  until $A logcat -d -s EPR:I | grep -q "EVT $1 "; do [ "$(date +%s)" -ge $end ] && return 1; sleep 0.5; done
}

# app_setup <apk>: install, clear data (unless CLEAR=0), grant all-files access, launch, wait for the first scan
# (EVENTS=0 for a build without bench events, e.g. debug: a fixed 20 s instead)
app_setup() {
  $A install -r "$1" >/dev/null || { echo "install of $1 failed" >&2; return 1; }
  [ "${CLEAR:-1}" = 0 ] || $A shell pm clear $PKG >/dev/null
  $A shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
  $A shell settings put system screen_off_timeout 1800000
  $A shell svc power stayon false
  $A shell input keyevent 224   # wake
  $A shell wm dismiss-keyguard
  $A logcat -c
  $A shell am start -W -n $PKG/app.epubreader.MainActivity >/dev/null
  SWIPE=$(swipe_xy)
  if [ "${EVENTS:-1}" = 1 ]; then wait_ev scan_complete 60 || echo "no scan_complete within 60 s" >&2; sleep 5
  else sleep 20; fi
}

# run_scenarios: a still page 60 s; b 20 turns 3 s apart; c1 library, first 60 s after Back; c2 library idle 60 s;
# d background 180 s after reading. Returns 1 (and sets ABORT) when the book does not open.
run_scenarios() {
  ABORT=
  $A logcat -c
  bench_cmd openPath "$BOOK"
  wait_ev painted_first 30 || { ABORT=no_painted_first; return 1; }
  sleep 15                                   # settle: first-open work done (Book pages: no screen-page count)
  if declare -F sc_probe >/dev/null; then sc_probe || { ABORT=${ABORT:-probe}; return 1; }; fi
  sleep 10                                   # a position write is at most 5 s behind a turn (C-108 e)
  $A logcat -c
  sc_begin a; sleep 60; sc_end a
  $A logcat -c
  sc_begin b; for _ in $(seq $TURNS); do swipe; sleep 3; done; sleep 2; sc_end b
  $A logcat -c
  sc_begin c1; $A shell input keyevent 4; sleep 60; sc_end c1
  $A logcat -c
  sc_begin c2; sleep 60; sc_end c2
  bench_cmd openPath "$BOOK"; wait_ev painted_first 30; sleep 20
  for _ in 1 2 3 4 5; do swipe; sleep 3; done
  sleep 3
  $A logcat -c
  sc_begin d; $A shell input keyevent 3; sleep 180; sc_end d
}
