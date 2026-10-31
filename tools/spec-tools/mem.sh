#!/usr/bin/env bash
# Usage: PKG=<app package> tools/mem.sh
# Print PSS/RSS (KB) of the app process and any separate renderer process(es).
A=${ADB:-adb}
for pid in $($A shell "ps -A -o PID,NAME | grep "${PKG:?set PKG to the app package}"" | awk '{print $1}'); do
  name=$($A shell "cat /proc/$pid/cmdline" | tr '\0' ' ' | cut -c1-60)
  $A shell dumpsys meminfo "$pid" | awk -v n="$name" '/TOTAL PSS:/ {print n, "PSS_KB=" $3, "RSS_KB=" $6}'
done
