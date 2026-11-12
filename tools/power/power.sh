#!/bin/bash
# Power audit measurement helpers (emulator). Sourced; requires the device lock held by the caller.
A=${ADB:-adb}
PKG=app.fernreader
OUT=${OUT:?}
RUNAS=${RUNAS:-0}   # 1 with the debuggable build: /proc/<pid>/io via run-as

pid_app() { $A shell pidof $PKG | tr -d '\r'; }
# WebView renderer(s): sandboxed processes forked from webview_zygote
pid_rend() { $A shell ps -A -o PID,NAME | awk '$2 ~ /^app\.fernreader:.*sandboxed/ {print $1}' | tr '
' ' '; }
# ticks (utime+stime, CLK_TCK=100) of pid
ticks() { [ -n "$1" ] && $A shell "cat /proc/$1/stat 2>/dev/null" | awk '{ sub(/^.*\) /, ""); print $12+$13 }'; }
sf_pid() { $A shell pidof surfaceflinger | tr -d '\r'; }
disk_w() { $A shell cat /proc/diskstats | awk '$3 ~ /^vd[a-z]$/ {s+=$10; n+=$8} END {print s*512, n}'; }
app_io() { [ "$RUNAS" = 1 ] && $A shell "run-as $PKG cat /proc/$1/io" 2>/dev/null | awk '/^(wchar|write_bytes|syscw)/ {printf "%s=%s ", $1, $2}'; }
frames() { $A shell dumpsys gfxinfo $PKG | awk -F': ' '/Total frames rendered/ {print $2; exit}'; }
uid() { $A shell dumpsys package $PKG | awk -F= '/appId=/ {print $2; exit}' | tr -d '\r'; }

snap() { # snap <tag>
  local pa pr sf
  pa=$(pid_app); pr=$(pid_rend); sf=$(sf_pid)
  local rt=0; for p in $pr; do rt=$((rt + $(ticks $p || echo 0))); done
  echo "$1 t=$(date +%s.%N | cut -c1-14) app_pid=$pa app_ticks=$(ticks $pa) rend_pids=[$pr] rend_ticks=$rt sf_ticks=$(ticks $sf) disk=$(disk_w | tr ' ' '/') io=[$(app_io $pa)]"
}

state() { # wakelocks, alarms, jobs, net for the app
  local u; u=$(uid)
  echo "--- power (wake locks with $PKG or uid $u)"
  $A shell dumpsys power | grep -iE "$PKG|uid=1?$u\b|mWakeLockSummary|mHoldingWakeLockSuspendBlocker|mHoldingDisplaySuspendBlocker|mStayOn|mScreenOffTimeoutSetting|mUserActivityTimeoutOverride" | sed 's/^ */  /'
  echo "--- alarms for $PKG: $($A shell dumpsys alarm | grep -c "$PKG")"
  $A shell dumpsys alarm | grep -E "$PKG" | head -5 | sed 's/^ */  /'
  echo "--- jobs for $PKG:"
  $A shell dumpsys jobscheduler | grep -E "$PKG" | head -8 | sed 's/^ */  /'
  echo "--- netstats uid $u:"
  $A shell dumpsys netstats detail | grep -A3 "uid=$u " | grep -E 'rb=|tb=' | head -5 | sed 's/^ */  /'
  echo "--- display mode request:"
  $A shell dumpsys display | grep -E 'mDisplayModeSpecs|appRequest' | head -2 | sed 's/^ */  /'
}

sc_begin() { # begin <scenario>
  echo "=== $1" | tee -a $OUT
  $A shell dumpsys gfxinfo $PKG reset >/dev/null
  snap before | tee -a $OUT
}
sc_end() {
  snap after | tee -a $OUT
  echo "frames=$(frames)" | tee -a $OUT
  state | tee -a $OUT
}
