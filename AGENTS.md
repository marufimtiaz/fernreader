# Working on FernReader

Short brief for any agent. Read this and `CLEANROOM.md` first, then the spec entry for your task.

## What this is

A fast, offline-first, MIT-licensed EPUB reader for Android. Kotlin Multiplatform and Compose Multiplatform, with
foliate-js in a WebView. `ui` and `core/*` are shared code; `reader-web` is the reader page; `app` is the Android shell.

## Rules

- **Source of truth:** `~/Projects/epub-reader-spec` only (`features.md`, `performance.md`, `clarifications.md`, C-ids).
  Never look at any other reader app. If the spec is silent or unclear, ask the gatekeeper; don't guess.
- **Work on your own branch in your own worktree.** Never merge, and never touch other worktrees.
- **Commit** in small steps with explicit paths (no `git add -A`), matching the surrounding code. Tests first where
  there's logic.
- **Report to the gatekeeper directly**, briefly: what works, how to run it, what isn't verified, and any decision
  needed. Report decisions before merging, not after. Don't report "still running".

## How to think

- **Read the code before running anything.** Explain a failure from the code. Reruns only confirm a fix.
- **Match effort to the change.** A small change gets unit tests and the affected test classes. The full suite and
  benchmarks run once per release, on the final code.
- **Never weaken a test to pass it.** Widen a wait only if the delay is in the test harness, not in the app.
- **A failure isn't noise until shown to be.** If a code reading doesn't explain it, say so; don't guess a cause.

## Fast loops (see docs/DEVELOPMENT.md)

- Reader and layout work: `npm run dev` in `reader-web`. Edit, see it in under a second.
- Screens: `tools/desktop.sh`.
- Phone: `tools/phone.sh`.
- Use the emulator last, for what only a device can show.

## Builds

- One Gradle build at a time on this machine: `GW_DIR=<worktree> ~/.cache/trackA/gw.sh <tasks>`. It holds the 2 GB heap,
  waits for 4 GB free, and queues behind device runs. Never stop Gradle daemons.
- Web: `cd reader-web; npm ci --no-audit --no-fund` once, then `npx vitest run`.

## Emulator

- Start it with `bench/emulator.sh` (host GPU, own systemd scope). Check it with `bench/emulator.sh check`.
- Take `mkdir /tmp/epub-reader-device.lock` and write your name into `owner`. Never hold it while waiting, release it
  on any exit (`trap`), and don't break someone else's lock. If it looks stale, ask.
- After taking it, install your APKs, `pm clear app.fernreader`, allow `MANAGE_EXTERNAL_STORAGE`, launch once, and wait
  for the scan.
- Suites: `tools/run_suite.sh` (runs on the device; `--no-corpus` for everyday runs).
- Don't open a T3 device session during UiAutomator tests.
- Don't use `pgrep -f` or `pkill -f` in loops: the pattern matches your own shell.

## Timing runs

Only when asked. Start when the CPU has been at least 80 % idle for 30 s, with no build or other device work. Record
idle with the run. A same-session run against a base build decides whether a failing target is ours.
