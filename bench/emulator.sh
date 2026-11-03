#!/usr/bin/env bash
# bench/emulator.sh — start the benchmark emulator (AVD Medium_Phone, emulator-5554) with HOST GPU rendering, and refuse
# to go on if it fell back to software rendering.
#
# Why: launched with `-gpu auto`, the emulator resolved to `lavapipe` (Mesa CPU Vulkan) after a host reboot, although
# the AVD's config.ini says hw.gpu.mode=host. Every frame was then rendered on the CPU: P-015 went from 28–37 ms to
# 140–200 ms, P-002 to 250–380 ms, on old and new builds alike, and qemu used ~170 % CPU (M9 part B, 2026-11-03).
# The mode the running emulator really uses is in the AVD's hardware-qemu.ini (rewritten at every launch).
#
# Usage: bench/emulator.sh            start headless (-no-window) with -gpu host; exit 1 if the mode isn't host
#        bench/emulator.sh check      only check the running emulator's GPU mode
set -u
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
A=$SDK/platform-tools/adb
AVD=${AVD:-Medium_Phone}
INI=$HOME/.android/avd/$AVD.avd/hardware-qemu.ini

check() {
  local mode; mode=$(sed -n 's/^hw\.gpu\.mode *= *//p' "$INI" 2>/dev/null)
  echo "hw.gpu.mode = ${mode:-?} ($INI)"
  [ "$mode" = host ] || { echo "emulator.sh: GPU mode is '${mode:-?}', not host: software rendering skews every timing" >&2; return 1; }
}

if [ "${1:-}" = check ]; then check; exit $?; fi
if pgrep -f "qemu-system.*-avd $AVD" >/dev/null; then echo "emulator.sh: $AVD is already running"; check; exit $?; fi
nohup "$SDK/emulator/emulator" -avd "$AVD" -no-audio -no-window -gpu host -no-boot-anim -port 5554 >/tmp/emulator-$AVD.log 2>&1 &
"$A" -s emulator-5554 wait-for-device
for _ in $(seq 1 120); do [ "$("$A" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
for _ in $(seq 1 60); do "$A" -s emulator-5554 shell ls /sdcard/Books >/dev/null 2>&1 && break; sleep 2; done # storage mounted
echo "booted: $("$A" -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')"
check
