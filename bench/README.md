# Milestone benchmark harness

Lean by design (C-015): only **Must** targets from performance.md, medians of 3 runs, no baseline file, no
regression rule, no jank/frame-time groups.

## When to run
- End of each milestone: `bench/run_all.sh <every group built so far>` (or `all`).
- Before release (M9): `bench/run_all.sh all --release` (also measures release-stage items).
- Never per build. For that use `tools/smoke.sh`.

## Prerequisites
- Emulator `Medium_Phone` running (serial `emulator-5554`).
- Device tool: `device_open` then `export AD=...` (groups that tap/type need it; `start` does not).
- Benchmark APK installed: `./gradlew :app:installPlayBenchmark`.
- Corpus pushed: `bench/push_corpus.sh`.
- Run with Gradle daemons stopped (`./gradlew --stop`) and, for groups that do not need agent-device (`start`, `mem`),
  with the T3 device-panel screen stream closed: its ffmpeg capture made P-002 read 248-346 ms instead of 108-175 ms
  with it closed (same build). The stream process can outlive the device session (M3): check `pgrep -a ffmpeg` before
  `start`; with it running, P-002/P-003 read 319/355 ms, without it 137/231 ms.

## Rules
- A failing group is re-run once before acting (about +-20 % emulator noise).
- `bench/gate.py` prints PASS/FAIL per id (median vs Must); exit 1 if anything fails. Unit tests:
  `python3 -I bench/test_gate.py`.
- Results are JSON Lines in `bench/results/` (git-ignored); copy the gate output into the milestone notes.
- Non-numeric measurements are recorded as 999999 (a certain FAIL) with the raw value in `note`.

## Groups

| Group | Spec IDs | Since |
|-------|----------|-------|
| `start` | P-001, P-002, P-003 (M3) | M1 |
| `open` | P-010..P-013, P-015, P-016 (P-014 with `--release`) | M2 |
| `action` | P-030, P-031; P-020, P-022, P-026 (+ blank frames) (M4) | M2 |
| `mem` | P-060 | M2 |
| `scan` | P-050 (+ first book visible), P-051, P-052 | M3 |
| `pagetotal` | P-021 (W&P, layout change -> exact total), estimate error | M4 |
| `search` | P-040..P-043 (event delta `search_cmd` -> `search_first` / `search_done`, 6 s after open; no agent-device) | M5 |

`bench/check_resume.sh` is a functional check (F-037/R-009), not a gate group.

`scan` clears the app's data (`pm clear`) before each run: it measures a first scan of the pushed corpus + folder set.

## Method deviations
- **P-021** is the event delta `cmd` (text size step) -> `page_total_exact` (the app's own event when the counted total
  becomes exact); the estimate error compares `page_total_estimate` with it.
- **P-022** rotates with an app-requested orientation (bench command `orient`), a real configuration change; the
  recording starts at the command marker, so it includes the system's rotation.
- **P-030** uses the event delta `cmd` -> first `painted` (logcat, tag EPR), not the spec's screen-recording method. A TOC jump
  between mostly white pages changes the screen by less than the recording analyzer's threshold, so the video reports NA or a tiny
  value. The events omit compositor/display latency (about 1-3 frames) after the web-side paint. The recording is still kept in
  `bench/results/video/` and its value is written in the note.

## Not in the harness
Checked once in M9, on a real device or by eye: P-025, P-027, the scroll clause of P-031, and P-063.
