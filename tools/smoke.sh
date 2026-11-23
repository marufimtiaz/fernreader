#!/usr/bin/env bash
# tools/smoke.sh — per-build check (C-015): host tests + the instrumented smoke test. Needs the emulator running.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew check
(cd reader-web && npx vitest run)
./gradlew :app:connectedPlayDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.fernreader.SmokeTest
echo "smoke: OK"
