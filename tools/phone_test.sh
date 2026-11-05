#!/usr/bin/env bash
# tools/phone_test.sh — tools/phone.sh against a fake adb (no phone needed).
set -uo pipefail
cd "$(dirname "$0")/.."
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fails=0
export ADB=$T/adb PHONE_STATE=$T/state PHONE_APK_DIR=$T/apk
unset PHONE_ADDR

cat > "$ADB" <<'FAKE'
#!/usr/bin/env bash
echo "adb $*" >> "$(dirname "$0")/calls"
[ "$1" = "-s" ] && shift 2
case "$1 ${2:-}" in
  "devices "*) echo "List of devices attached"; cat "$(dirname "$0")/devices" 2>/dev/null ;;
  "connect "*) if [ "$2" = "10.0.0.9:5555" ]; then echo "failed to connect to $2"; else echo "connected to $2"; fi ;;
  "disconnect "*) echo "disconnected $2" ;;
  "install "*) [ -e "$(dirname "$0")/badsig" ] && { echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: x]"; exit 1; }; echo Success ;;
  "shell getprop") echo 34 ;;
  "shell pm") echo "package:app.fernreader uid:10123" ;;
  "shell am") echo "Starting" ;;
  "logcat "*) echo "log" ;;
esac
FAKE
chmod +x "$ADB"

# check <name> <expected exit> <text expected in the output> -- <args>
check() {
  local name=$1 code=$2 text=$3; shift 4
  : > "$T/calls"
  local out rc=0
  out=$(tools/phone.sh "$@" 2>&1) || rc=$?
  if [ "$rc" != "$code" ] || [[ $out != *"$text"* ]]; then
    echo "FAIL $name: exit $rc, output: $out"; fails=$((fails + 1))
  else echo "ok   $name"; fi
}
called() { if grep -qF -- "$2" "$T/calls"; then echo "ok   $1"; else echo "FAIL $1: $(cat "$T/calls")"; fails=$((fails + 1)); fi; }
not_called() { if grep -qF -- "$2" "$T/calls"; then echo "FAIL $1: $(cat "$T/calls")"; fails=$((fails + 1)); else echo "ok   $1"; fi; }

check "no arguments prints the usage" 0 "Usage:" --
check "an unknown command fails" 2 "Usage:" -- frobnicate
check "pair prints the steps with the fish form" 0 "set -gx PHONE_ADDR" -- pair
check "connect needs an address" 1 "which phone" -- connect
check "connect refuses an emulator" 1 "is an emulator" -- connect emulator-5554
not_called "and adb was not asked" "connect"
check "connect refuses a bad address" 1 "not an address" -- connect 192.168.1.20
check "connect reports a failure" 1 "could not connect" -- connect 10.0.0.9:5555
check "connect works" 0 "connected to" -- connect 192.168.1.20:41235
check "connect without an address uses the last one" 0 "connected to 192.168.1.20:41235" -- connect
rm "$PHONE_STATE"

check "no phone" 1 "no phone connected" -- run
printf 'emulator-5554\tdevice\n' > "$T/devices"
check "an emulator is not a phone" 1 "no phone connected" -- run
check "devices leaves emulators out" 0 "" -- devices
PHONE_ADDR=emulator-5554 check "PHONE_ADDR may not be an emulator" 1 "is an emulator" -- run
printf 'emulator-5554\tdevice\n192.168.1.20:41235\tdevice\n' > "$T/devices"
check "the only phone is used" 0 "Starting" -- run
called "with -s, and the right activity" "-s 192.168.1.20:41235 shell am start -n app.fernreader/app.epubreader.MainActivity"
printf '192.168.1.20:41235\tdevice\n192.168.1.21:41235\tdevice\n' > "$T/devices"
check "two phones need a choice" 1 "several phones" -- run
PHONE_ADDR=192.168.1.21:41235 check "PHONE_ADDR chooses" 0 "Starting" -- run
called "the chosen one" "-s 192.168.1.21:41235 shell am"
PHONE_ADDR=192.168.1.99:1 check "PHONE_ADDR not connected" 1 "is not connected" -- run
printf '192.168.1.20:41235\tdevice\n192.168.1.21:41235\toffline\n' > "$T/devices"

check "install with nothing built" 1 "no APK under" -- install
mkdir -p "$T/apk/play/debug" "$T/apk/fdroid/release" "$T/apk/androidTest/play/debug"
touch -d '2 hours ago' "$T/apk/play/debug/old.apk"
touch -d '1 hour ago' "$T/apk/play/debug/app-play-debug.apk"
touch "$T/apk/androidTest/play/debug/test.apk" "$T/apk/fdroid/release/app-unsigned.apk"
check "install takes the newest app APK" 0 "Success" -- install
called "not a test or unsigned APK" "install -r $T/apk/play/debug/app-play-debug.apk"
check "install takes a given APK" 1 "no such file" -- install /nonexistent.apk
touch "$T/badsig"
check "a signature mismatch never uninstalls" 1 "Uninstalling loses its data" -- install
not_called "uninstall was not run" " uninstall"
rm "$T/badsig"

check "logs filter by uid" 0 "log" -- logs
called "the app's uid" "logcat -v time --uid=10123"
check "logs --all" 0 "log" -- logs --all
check "logs refuses other flags" 1 "only --all" -- logs --wat
check "disconnect names the phone" 0 "disconnected" -- disconnect
called "never all of them" "disconnect 192.168.1.20:41235"

if grep -nE 'bench|epub-reader-device\.lock' tools/phone.sh | grep -v '^3:'; then echo "FAIL: phone.sh mentions bench or the device lock"; fails=$((fails + 1)); fi

[ "$fails" = 0 ] && echo "phone_test: OK" || { echo "phone_test: $fails failed"; exit 1; }
