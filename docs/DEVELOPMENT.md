# Development tools

Three ways to see a change without the full Android cycle. None of them is part of any APK. Commands are for fish.

## Reader in a browser

```fish
cd reader-web; npm ci --no-audit --no-fund    # once
npm run dev                                   # http://localhost:5173/
```

The page at `/dev/` holds the reader in a phone-sized frame, with controls beside it: book picker, contents, progress,
paged/scrolled, chapters, text size, margins, line height, font, alignment, theme, viewport size, and a log of the bridge
messages. Taps on the page turn it as in the app (outer quarters; mirrored for right-to-left books), and so do the arrow keys.

Edit anything under `reader-web/src` or `reader-web/vendor` and save: the frame reloads and the book reopens where it was.
The page shows the time from the file's write to the first painted page; the terminal prints it too. It took about
0.2 to 0.6 s here. Position and settings are kept in the browser's local storage; **From the start** forgets the position.
`?book=<id>` opens a book directly (ids from `/__dev/books`).

Covers: the real `src/index.html` and `main.ts`, unchanged, with their CSP, in a frame; the book loader, foliate-js and the
paginator; books from `corpus/gutenberg`, `corpus/epub3-samples` and `corpus/generated` (`CORPUS_DIR` overrides the folder).

How it fits: `dev/plugin.ts` answers `/book/<id>/…` like `BookContentServer.kt` (`dev/bookServer.ts`, `dev/zip.ts`) and puts
`dev/shim.js` in the reader page as the stand-in for the `ReaderBridge` object; `dev/host.ts` plays the app's side
(`open` on `ready`, positions, settings, taps). `dev/vite.config.ts` is used by `npm run dev` only; `npm run build` keeps
using `vite.config.ts`, whose root is `src`, so no file under `dev/` can reach the build. `npm run typecheck:dev` checks
`dev/`; its logic has Vitest tests (`test/dev-*.test.ts`).

Does not cover:

- Android WebView itself: this is your desktop browser's engine and version. Check touch, text selection handles, and anything
  engine-specific on a device.
- The app's chunking of huge documents (M8): a book is served as its plain zip, so `stress-huge-single-chapter.epub` opens
  unsplit and slower than in the app.
- Swipes need touch input (use the browser's device emulation); the dev page has no bookmarks, annotations or search.
- Zip64 files.

The three broken files in `corpus/generated` that are not zips answer 500 here, so the page shows `open_failed`; the app
rejects them before the reader page is involved.

## UI on desktop

`:devtools:desktop` runs the shared `ui` code in a window: the real `App()`, `LibraryState`, `SettingsState` and
`ReaderController` over a temporary Room database with sample books, a sample scan failure and collections. Nothing in `:app`
depends on the module, and no data outlives the window.

```fish
tools/desktop.sh            # with Compose Hot Reload
tools/desktop.sh --plain    # a plain run on any JDK (./gradlew :devtools:desktop:run)
./gradlew :devtools:desktop:screenshots    # PNGs of the screens in devtools/desktop/build/screenshots; needs no display
```

Hot reload works with Kotlin 2.4.20 and Compose Multiplatform 1.10.0, which bundles Compose Hot Reload 1.0.0. Save a file
under `ui/` and the running window takes the new code, keeping its state. It needs the JetBrains Runtime 21 (open source,
GPLv2 with Classpath Exception; dev only): on the first run `tools/desktop.sh` downloads it to
`~/.cache/fernreader-jbr`, checks its SHA-512, and passes it to Gradle. A second Gradle process recompiles in the background.

A reload is not instant: `ui` is one module and Gradle has to compile it. An edit in `LibraryScreen.kt` took 66 s, 32 s and
20 s to reach the window on a busy machine with a 2 GB Gradle heap (the first is the slowest). The browser page above is the
fast loop; this one is for Compose work.

Esc is the system Back. A click on the reader placeholder shows the reader's bars.

Covers: library (grid, list, search, sort, selection, collections, folders), drawer, settings, hidden books, about, licences,
book details, and the reader's bars and panels. On Hyprland the window runs under Xwayland.

Does not cover:

- The reader surface: a placeholder. A fake reader answers `open` with five sections, so the bars have something to show, but
  no text is laid out and search, bookmarks and contents have no real book behind them.
- Android: system pickers, permissions and onboarding, backup and restore, insets, back gestures, touch. Those settings entries
  do nothing here, and `Where to look for books` uses a fixed folder.
- Scanning and cover extraction: the books are sample rows and the covers are the generated placeholders.

## Phone

`tools/phone.sh` works with a real phone over wireless adb. It refuses emulators, and has nothing to do with the bench
scripts or the emulator's device lock.

```fish
tools/phone.sh pair                          # prints the pairing steps; you do them on the phone, once
tools/phone.sh connect 192.168.1.20:41235    # the address on the Wireless debugging screen
./gradlew :app:assemblePlayDebug
tools/phone.sh install                       # the newest APK under app/build/outputs/apk; or give a path
tools/phone.sh run
tools/phone.sh logs                          # this app's log; --all for the whole device
```

`connect` without an address uses the last one. With several phones, `set -gx PHONE_ADDR <ip:port>` picks one. The connect
port changes when Wireless debugging is turned off and on. An APK with another signature than the installed one
fails with a hint: the script never uninstalls, since that loses the app's data.

Does not cover: pairing (a step on the phone), building, and instrumented tests. `tools/phone_test.sh` tests the script
against a fake adb; it needs no phone.
