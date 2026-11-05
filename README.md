# FernReader

A fast, offline-first EPUB reader for Android. Free software under the MIT licence (see `LICENSE` and
`docs/licenses.md` for third-party components).

- Finds the EPUB books on the device and opens them quickly; no account, no network, no tracking.
- Kotlin Multiplatform + Compose Multiplatform for the app, [foliate-js](https://github.com/johnfactotum/foliate-js)
  (MIT) in a WebView for rendering.
- applicationId `app.fernreader` (flavors `play` and `fdroid`); the Kotlin packages are `app.epubreader.*`.

## Build

```sh
./gradlew :app:assembleFdroidRelease    # unsigned F-Droid build (signing: docs/release-checklist.md)
./gradlew check                         # unit tests, lint, checkFdroidClean
```

Needs JDK 17+, the Android SDK (compileSdk 37) and Node.js/npm (the reader's web part in `reader-web/` is built by
Gradle with `npm ci`).

## Docs

- `docs/DEVELOPMENT.md`: the reader in a browser, the UI on desktop, a phone over wireless adb.
- `CLEANROOM.md`: how this clean-room implementation is developed (binding for contributors).
- `bench/README.md`: the benchmark harness; `docs/release-checklist.md`, `docs/real-device-pass.md`: release.
- `docs/milestones/`: milestone notes and results.
