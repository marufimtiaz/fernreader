#!/usr/bin/env bash
# bench/check_resume.sh — functional F-037/R-009 check (not a timing gate): 27 turns in War and Peace,
# force-stop, reopen → every relocate after the reopen has the CFI of the last one before the kill.
RESULTS=/dev/null source "$(dirname "$0")/lib.sh"
last_cfi() { $A logcat -d -s EPR:I | awk '/EVT relocate /{ c=$NF } END { print c }'; }
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
for i in $(seq 1 27); do bench_cmd next; sleep 0.5; done; sleep 1
want=$(last_cfi)
$A shell am force-stop "$PKG"; $A logcat -c
$A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
# Strict: the web side sends no transient relocate while seeking to the saved CFI (bridge-protocol.md), so every
# relocate after the reopen, the first included, must be the saved CFI (nothing else may reach the database).
got=$($A logcat -d -s EPR:I | awk '/EVT relocate /{ print $NF }' | sort -u | tr '\n' ' ' | sed 's/ $//')
echo "want=$want got=$got"; [ -n "$want" ] && [ "$want" = "$got" ] && echo "resume: PASS" || { echo "resume: FAIL"; exit 1; }
