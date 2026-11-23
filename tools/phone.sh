#!/usr/bin/env bash
# tools/phone.sh — a real phone over wireless adb: connect, install the latest build, tail the logs. See docs/DEVELOPMENT.md.
# Only for a phone: it refuses emulators, takes no device lock and has nothing to do with the bench scripts.
set -euo pipefail

PACKAGE=app.fernreader
ACTIVITY=app.fernreader.MainActivity
ROOT=$(cd "$(dirname "$0")/.." && pwd)
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
APK_DIR=${PHONE_APK_DIR:-$ROOT/app/build/outputs/apk}
STATE=${PHONE_STATE:-${XDG_CACHE_HOME:-$HOME/.cache}/fernreader-phone}

usage() {
  cat <<'USAGE'
Usage: tools/phone.sh <command>

  pair                print how to pair a phone (you do this step on the phone)
  connect [ip:port]   connect to the phone; without an address, the last one
  devices             list the phones adb sees (emulators are not listed)
  install [apk]       install the newest built APK, or the given one
  run                 start the app
  logs [--all]        follow the app's log (--all: the whole device log)
  disconnect          disconnect the phone

The phone is the one in $PHONE_ADDR if set, else the last one connected, else the only phone adb sees.
USAGE
}

die() { echo "phone.sh: $*" >&2; exit 1; }

pair_steps() {
  cat <<STEPS
Pair once per phone and computer (the commands are for fish):

1. On the phone: Settings > About phone, tap Build number seven times.
2. Settings > System > Developer options: turn on Wireless debugging, on the same Wi-Fi as this computer.
3. Tap Wireless debugging > Pair device with pairing code. It shows a code and an address like 192.168.1.20:37099.
4. Here, with that address, then enter the code when asked:

     $A pair 192.168.1.20:37099

5. Back on the Wireless debugging screen, note the IP address & Port there (another port, like 192.168.1.20:41235):

     tools/phone.sh connect 192.168.1.20:41235

6. Then:

     tools/phone.sh install; and tools/phone.sh run; and tools/phone.sh logs

The connect port changes when Wireless debugging is turned off and on: connect again with the new one. To pin a phone
for this shell:

     set -gx PHONE_ADDR 192.168.1.20:41235
STEPS
}

valid_addr() { [[ $1 =~ ^[A-Za-z0-9._-]+:[0-9]{1,5}$ ]]; }
is_emulator() { [[ $1 == emulator-* ]]; }

# Serials in state `device`, emulators left out.
phones() {
  "$A" devices | awk 'NR > 1 && $2 == "device" { print $1 }' | grep -v '^emulator-' || true
}

# The phone to talk to, on stdout.
target() {
  local want=${PHONE_ADDR:-} list
  if [ -z "$want" ] && [ -f "$STATE" ]; then want=$(cat "$STATE"); fi
  list=$(phones)
  if [ -n "$want" ]; then
    if is_emulator "$want"; then die "$want is an emulator; this script is for a phone"; fi
    if grep -qxF "$want" <<<"$list"; then echo "$want"; return; fi
    if [ -n "${PHONE_ADDR:-}" ]; then die "$want is not connected: tools/phone.sh connect $want"; fi
  fi
  case $(grep -c . <<<"$list" || true) in
    0) die "no phone connected: tools/phone.sh connect <ip:port> (first time: tools/phone.sh pair)" ;;
    1) echo "$list" ;;
    *) die "several phones: set PHONE_ADDR to one of: $(tr '\n' ' ' <<<"$list")" ;;
  esac
}

connect() {
  local addr=${1:-${PHONE_ADDR:-}}
  if [ -z "$addr" ] && [ -f "$STATE" ]; then addr=$(cat "$STATE"); fi
  [ -n "$addr" ] || die "which phone? tools/phone.sh connect <ip:port> (first time: tools/phone.sh pair)"
  if is_emulator "$addr"; then die "$addr is an emulator; this script is for a phone"; fi
  valid_addr "$addr" || die "not an address: $addr (expected ip:port, as on the phone's Wireless debugging screen)"
  local out
  out=$("$A" connect "$addr")
  echo "$out"
  [[ $out == *"connected to"* ]] || die "could not connect; check the port on the Wireless debugging screen (it changes), or pair first"
  mkdir -p "$(dirname "$STATE")"
  echo "$addr" > "$STATE"
}

# The newest installable APK: not a test APK, not an unsigned release.
latest_apk() {
  [ -d "$APK_DIR" ] || return 0
  find "$APK_DIR" -name '*.apk' -not -path '*/androidTest/*' -not -name '*-unsigned.apk' -printf '%T@ %p\n' | sort -n | tail -1 | cut -d' ' -f2-
}

install() {
  local apk=${1:-}
  if [ -z "$apk" ]; then apk=$(latest_apk); fi
  [ -n "$apk" ] || die "no APK under $APK_DIR: build one first (./gradlew :app:assemblePlayDebug)"
  [ -f "$apk" ] || die "no such file: $apk"
  local phone out
  phone=$(target)
  echo "installing $apk on $phone"
  if ! out=$("$A" -s "$phone" install -r "$apk" 2>&1); then
    echo "$out" >&2
    if [[ $out == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]]; then
      die "the installed app has another signature. Uninstalling loses its data, so do that yourself: $A -s $phone uninstall $PACKAGE"
    fi
    die "install failed"
  fi
  echo "$out"
}

logs() {
  local phone sdk pid uid
  phone=$(target)
  if [ "${1:-}" = "--all" ]; then exec "$A" -s "$phone" logcat -v time; fi
  [ -z "${1:-}" ] || die "logs takes only --all"
  sdk=$("$A" -s "$phone" shell getprop ro.build.version.sdk | tr -d '\r')
  if [ "${sdk:-0}" -ge 29 ]; then
    # By uid, so the log follows the app across restarts.
    uid=$("$A" -s "$phone" shell pm list packages -U "$PACKAGE" | tr -d '\r' | sed -n "s/^package:$PACKAGE uid:\([0-9]*\).*/\1/p")
    [ -n "$uid" ] || die "$PACKAGE is not installed on $phone: tools/phone.sh install"
    exec "$A" -s "$phone" logcat -v time --uid="$uid"
  fi
  pid=$("$A" -s "$phone" shell pidof "$PACKAGE" | tr -d '\r')
  [ -n "$pid" ] || die "$PACKAGE is not running on $phone: tools/phone.sh run"
  exec "$A" -s "$phone" logcat -v time --pid="$pid"
}

case ${1:-} in
  pair) pair_steps ;;
  connect) [ $# -le 2 ] || die "connect takes one address"; connect "${2:-}" ;;
  devices) phones ;;
  install) [ $# -le 2 ] || die "install takes one APK"; install "${2:-}" ;;
  run) phone=$(target); "$A" -s "$phone" shell am start -n "$PACKAGE/$ACTIVITY" ;;
  logs) [ $# -le 2 ] || die "logs takes only --all"; logs "${2:-}" ;;
  disconnect) phone=$(target); "$A" disconnect "$phone" ;;
  -h|--help|help|'') usage ;;
  *) usage >&2; exit 2 ;;
esac
