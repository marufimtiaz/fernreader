#!/usr/bin/env bash
# bench/groups/start.sh — P-001 cold start, P-002 hot start, P-003 open-with (warm) (performance.md: am start -W
# TotalTime). 3 runs each. P-003: the VIEW activity's first frame is the reader frame with its progress indicator.
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
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
VIEW_BOOK=file://$B/gutenberg/alice-epub3-images.epub
for RUN in 1 2 3; do
  $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 2 # process running, library in front
  $A shell input keyevent KEYCODE_HOME; sleep 2
  v=$($A shell am start -W -a android.intent.action.VIEW -t application/epub+zip -d "$VIEW_BOOK" -n "$PKG/$ACT" | awk '/TotalTime/ {print $2}')
  emit P-003 "${v:-999999}"
  sleep 3; $A shell input keyevent KEYCODE_BACK; sleep 1
done
