#!/usr/bin/env bash
# Total PSS in MB of the app process plus its WebView renderer(s) (process names start with the package).
# Usage: PKG=<pkg> tools/mem_total.sh   -> "app_kb renderer_kb total_mb"
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
app=0; ren=0
for pid in $($A shell "ps -A -o PID,NAME | grep ${PKG:?}" | awk '{print $1}'); do
  name=$($A shell "cat /proc/$pid/cmdline" | tr '\0' ' ')
  kb=$($A shell dumpsys meminfo "$pid" | awk '/TOTAL PSS:/ {print $3}')
  case "$name" in *sandboxed*|*:privileged*) ren=$((ren + ${kb:-0}));; *) app=$((app + ${kb:-0}));; esac
done
echo "app_kb=$app renderer_kb=$ren total_mb=$(( (app + ren) / 1024 ))"
