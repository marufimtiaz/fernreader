#!/usr/bin/env bash
# bench/groups/start.sh — P-001 cold start, P-002 hot start (performance.md: am start -W TotalTime). 3 runs each.
source "$(dirname "$0")/../lib.sh"
for RUN in 1 2 3; do
  $A shell am force-stop "$PKG"; sleep 1
  v=$($A shell am start -W -n "$PKG/$ACT" | awk '/TotalTime/ {print $2}')
  emit P-001 "${v:-999999}"
done
for RUN in 1 2 3; do
  $A shell input keyevent KEYCODE_HOME; sleep 2
  v=$($A shell am start -W -n "$PKG/$ACT" | awk '/TotalTime/ {print $2}')
  emit P-002 "${v:-999999}"
done
