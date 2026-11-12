# Power audit (v1.1-dev at 00c65fe)

Research only: no app code was changed. The spec pass decides the fixes. Part 1 comes from reading the code, Part 2
from measurements on the emulator, and Part 3 covers what only a real phone can show.

"Normal reading session" here means the screen is on and the user turns a page every 30–60 s, in the default settings:
Light theme, Slide animation, keep-screen-on on, fullscreen, Book pages numbering, brightness following the system.

## Ranking (normal reading session, most battery first)

The display is by far the largest cost in a reading session. Everything the app itself does while a page is still adds
up to well under 1 % of one core. So the top items are display settings and defaults; the app's own waste comes after.

| Rank | Item | Verdict | Why it ranks here |
|------|------|---------|-------------------|
| 1 | D1: Light theme by default; true black (`BLACK`) has to be chosen | Needed, but the default is the costliest on OLED | Panel power scales with lit pixels. A near-white page vs true black is the biggest single lever, often hundreds of mW for the whole session. |
| 2 | D2: keep-screen-on with no idle limit (on by default) | Needed (F-035), but unbounded | Costs nothing while reading. A book left open keeps the screen on until the battery is flat. That is the worst case on a low battery. |
| 3 | D3: no refresh-rate request; a still page runs at 120 Hz on fixed-mode panels | Clear waste on those panels | About 5–15 % more panel power for the whole session on such phones. Nothing changes on LTPO panels or on the emulator (60 Hz only). |
| 4 | T1: `sample(150)` ticker in `libraryLive`, always on | **Clear waste** | Every second of the session, in the reader too: about 34 wakeups/s and 0.3 s of CPU per minute, which is all of the app's measured idle CPU. |
| 5 | C1: screen-page counter lays out the whole book on every open and every layout change; its results are not stored, and they are unused with Book pages (the default) | **Clear waste** in the default mode and on reopen | 2–10 s of CPU per open or settings change (measured: Moby-Dick 4.6 s, War and Peace 8.6 s on first open, 8.4 s on reopen, 6.6 s per text-size step). One-off per event, at full CPU speed. |
| 6 | F2: turn animation (Slide) plus the per-turn layout and paint | Needed | About 0.1 s of renderer CPU and about 11 frames per turn on the emulator (18–20 at 60 Hz, 36–40 at 120 Hz on a phone). At one turn per 30–60 s, under 1 % duty. |
| 7 | W1: position saved on every turn (one SQLite transaction plus fsync) | Needed (R-009), could be batched | Measured 16 KB written per turn. It is the only periodic disk write, one storage wakeup per turn. |
| 8 | S1: library scan on every return to the foreground (in the reader too) and every return to the library | Partly waste (spec question: F-012/C-075) | Small on the emulator (28–47 ms walk, 60 files), but it grows with the number of folders on a real phone. In the reader it buys nothing for the open book. |
| 9 | T2: 1 Hz `stat` poll of the open book file | Partly waste | About 1.25 wakeups/s, under 0.01 s CPU per minute. Cheap, but a timer that never stops while a book is open. |
| 10 | O1: `SENSOR_PORTRAIT/LANDSCAPE` with an orientation lock keeps the accelerometer on even with auto-rotate off | Small | Only with a lock chosen. About 0.1–1 mW. |
| — | Network, alarms, jobs, services, partial wakelocks | None | 0 bytes and none registered. The only wakelock is the screen one from keep-screen-on. The search-index job is idle plus charging only. |

## Part 1: code reading

Costs are rough orders of magnitude for a mid-range phone. "Waste" means the work buys the reader nothing in that
situation. "Needed" means it is a cost of a feature the spec asks for, though it may still be trimmed.

### 1. Display (the largest cost while reading)

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| D1 | `core/data/.../ReaderSettings.kt:54`: `theme = LIGHT` by default. The page is `#FBFAF7` (`ui/.../display/ReadingTheme.kt`, `reader-web/src/reader/styles.ts:37`) | Whole session | On OLED, a near-white full screen costs roughly 3–6× the panel power of black text on true black. That is often 100–400 mW, the biggest single item in a reading session. The `BLACK` theme (`#000000`, text `#C4C0B8`) exists but has to be chosen. Grey `DARK` (`#212427`) keeps every pixel lit, so it saves much less than `BLACK` on OLED. | Needed (user choice), but the default is the costliest option on OLED |
| D2 | `ReaderSettings.kt:65`: `keepScreenOn = true` by default. Applied in `app/.../MainActivity.kt:398` (`FLAG_KEEP_SCREEN_ON` while a book is shown, `ReaderWindowState.kt:43`) | Whole session with a book on screen | While reading actively, nothing. With the phone put down on an open book, the screen stays on until the battery runs out, at the full display cost (D1). It has no idle limit. | Needed (F-035, on by default), but unbounded. The riskiest item for a low battery. |
| D3 | No refresh-rate request anywhere: no `preferredRefreshRate`, `preferredDisplayModeId`, `Surface.setFrameRate` or `frameRate` on the window or view (grep finds none) | Whole session | On LTPO/VRR panels the system already drops the rate when nothing draws, so it costs nothing extra. On fixed-mode 90/120 Hz panels (many mid-range phones with "smooth display" on), the panel scans out at 120 Hz with a still page. That is about 5–15 % more panel power than 60 Hz on such phones. The emulator only has 60 Hz (see Part 2). | Clear waste on fixed-mode high-rate panels. A still page does not need 120 Hz, though C-060 wants turn animations smooth at the native rate. |
| D4 | Brightness: `MainActivity.kt:247` and `387`. `null` (the default) follows the system, so auto-brightness keeps working. A user-set value overrides auto-brightness for the reader window. | Whole session | None by default. With a fixed value the brightness does not drop in a darker room. | Needed |

### 2. Frames drawn while the page is still

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| F1 | No infinite animations, blinking cursors or tickers in the reader. The only `CircularProgressIndicator` is the loading screen (`ui/.../ReaderScreen.kt:95`, only while `loading`). No `rememberInfiniteTransition` in the code. The library's `LinearProgressIndicator` (`ui/.../LibraryScreen.kt:178`) is shown only when a scan finds something (`showsProgress`). | — | None: a still reader page draws 0 frames (Part 2a). | — |
| F2 | Turn animation: `reader-web/vendor/foliate-js/paginator.js:144-173` (`animate`, one `requestAnimationFrame` per frame) and `:57` (`SLIDE_MS = 300`) | Each turn with Slide (the default) | About 18–20 frames per turn at 60 Hz, 36–40 at 120 Hz, then the `painted` double rAF (`reader.ts:806`). With a turn every 30–60 s that is a duty cycle under 1 %. | Needed (F-032). "None" saves a little. |
| F3 | `app/.../ReaderSurface.kt:69-77`: the WebView stays `VISIBLE` (alpha 0) under the library while the app is in front (C-089), so its page keeps running | Library on screen with the reader page loaded behind it | The render thread skips an alpha-0 draw. Chromium still treats the page as visible, so its timers and rAF still run, but the page is idle and the measured cost is low (Part 2c). | Needed (warm start, P-002/C-089) |
| F4 | `ResizeObserver`s in foliate (`paginator.js:391`, `:677`; `fixed-layout.js:35`) | Only on a real resize: rotation, bars, the inset changing | They re-render and re-paginate the section, and a layout change also restarts the page counter (C1) | Needed |

### 3. JavaScript timers, rAF and observers (reader-web, foliate-js)

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| J1 | Nothing runs while idle: every `setTimeout`/`requestAnimationFrame` in `reader-web/src` and the vendored foliate is a one-shot tied to an event (turn, settings, selection, paint). There is no `setInterval`, no `requestIdleCallback`, no polling, and no `MutationObserver` or `IntersectionObserver`. | — | 0 when idle. Confirmed by Part 2a. | — |
| J2 | `reader-web/src/reader/selection.ts:53`: 120 ms debounce on selection changes | Only while selecting | Negligible | Needed |
| J3 | `foliate-js/view.js:141`: `CursorAutohider` 1 s timeout | Mouse moves only. It never fires on touch. | 0 on a phone | — |
| J4 | `reader.ts:723`: neighbours restyled after the visible page has painted | Each settings change | One reflow of 1–2 hidden sections | Needed |

### 3b. Kotlin timers that tick while idle (found by measurement, Part 2e)

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| T1 | `core/data/.../LibraryRepository.kt:105-108` (`libraryLive`): `added...sample(150)`. kotlinx `sample(period)` runs a fixed-period ticker for as long as the flow is collected, whether or not values arrive. The upstream (`scans.state.map { it.found }`, `MainActivity.kt:202`) never completes. `LibraryState.ui` (`ui/.../library/LibraryState.kt:111-138`) collects it with `SharingStarted.Eagerly` in the activity's `lifecycleScope`. Each collection of the flow runs its own ticker. `allBooks` (`LibraryState.kt:108`) and `SettingsState`'s `books` are the same cold flow. One ticker would give about 6.7 timer wakeups/s; 16.5/s were measured on `DefaultExecutor`. Whether a second collector or something else makes up the rest was not traced. | **The whole life of the activity**: on the library, in the reader, and in the background until the system freezes the process | Measured on a still reader page: about 34 thread wakeups/s (`DefaultExecutor` + `DefaultDispatcher`) and 0.3 s of CPU per minute. That is all of the app's idle CPU. Each wakeup takes a core out of its idle state. A ticker every 150 ms is only needed while a scan is adding books. | **Clear waste** outside a running scan |
| T2 | `app/.../library/FileWatch.kt:65`: `scheduleWithFixedDelay(::check, 1000, 1000)`. It stats the book file on FUSE once a second because FUSE sends no inotify events. | While a book is open, foreground and background (until frozen) | Measured: about 1.25 wakeups/s on the `file-watch` thread, under 0.01 s CPU per minute. Each `stat` on `/storage/emulated` goes through the FUSE daemon, so the system pays a little too. | Partly waste. R-008 needs the check, but checking on resume and on each turn or section load would catch the same cases without a 1 Hz timer. |

### 4. Background work in the reader: the page counter, statistics and book pages

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| C1 | **Screen-page counter.** `reader-web/src/reader/reader.ts:809` starts it after the first paint of **every** open. `reader.ts:767-775` reruns it 400 ms after **every** layout change: text size, font, line height, margins, alignment, `topInset`, and also rotation and resize via the paginator. The work is in `reader-web/src/reader/counter.ts` (`PageCounter.start`): it lays out the whole book in hidden iframes in batches of 24 sections (or 512 KB), plus one section counted alone in a foliate `View` per batch (C-003 self-check). Books where the check fails (`bookAlone`) count every section alone. | Each open, and each layout change, for the whole book | Main-thread and renderer CPU proportional to book size: a full layout of the book's text. Part 2 has the measured cost. **The results are never stored**, so reopening the same book with the same settings counts it again. In the default numbering (**Book pages**, `ReaderSettings.kt:58`), the screen counts are not shown at all (`ReaderController.kt:246-247` shows `bookNumbers` unless the book's page data is missing). They only feed the fallback and Screen numbering. | **Clear waste** when Book pages are in use and the counts are unused, or when it recounts a layout it already counted. Needed only for Screen numbering. |
| C2 | Section stats and book pages: `ReaderController.kt:365` (`loadStats`) and `:391-398` (`bookPages`, stored with `library.storeBookPages`) | Once per open. Results are cached in `book_cache` / `book_pages`, so later opens read the database. | First open: one pass over the book's text in Kotlin (I/O plus parsing). Later opens: a database read. | Needed, and already cached |
| C3 | Chunking huge chapters (`app/.../reader/BookOpener.kt:30-32`) | First open of a book with an oversized spine item. The chunk cache is reused later. | One-time | Needed |
| C4 | Search index (`app/.../reader/SearchIndexWorker.kt:39-43`, `ui/.../search/ReaderSearch.kt:181`) | Built only when the user searches. An unfinished index is finished by WorkManager with **device idle + charging** constraints. | 0 on battery | Needed, and well placed |

### 5. Disk writes

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| W1 | Reading position: `ui/.../ReaderController.kt:1149` → `core/data/.../PositionWriter.kt` (conflated channel) → `PositionDao.kt:23` (`put`, one Room `@Transaction`: a read plus an upsert) | **Every `relocate`**, so every page turn. Bursts collapse, but one turn every 30–60 s means one transaction per turn. | One SQLite WAL transaction per turn: a few KB written plus an fsync on commit. Part 2b has the measured bytes. On flash each fsync wakes the storage controller (a few mJ). Small per turn, but it is the only periodic write. | Needed for R-009 (at most the last page lost). Writing at most every N seconds or N turns, plus on `onStop`, would keep R-009 if `onStop` flushes. |
| W2 | Settings: `ReaderController.kt:515-518` | Each settings change, debounced 300 ms | Negligible | Needed |
| W3 | Bookmarks, annotations, notes (`flushNote` on `onStop`, `MainActivity.kt:558`) | User actions only | Negligible | Needed |
| W4 | `MainActivity.kt:237` (`AppThemeCache.write`) | When the app theme mode changes, and once per composition start | One small file | Needed |
| W5 | Cover thumbnails (`app/.../library/CoverExtractor.kt`, `CoverQueue`) | After a scan, for books without a cover only. A book with no usable cover is marked `""` and never retried. Paused while a book is open (`MainActivity.kt:410`). | One decode plus WebP encode per new book | Needed |

### 6. Work that starts outside the reader: scans, jobs, services

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| S1 | Incremental library scan: `core/data/.../ScanController.kt:61-64`, triggered from `MainActivity.kt:546` (`onForeground` on **every** return to the foreground, on any screen, the reader too) and `MainActivity.kt:260` (`onViewShown` every time the library comes into view, including Back from the reader). The default scope is all of shared storage (`ScanScope.Everywhere`). The walker (`core/data/.../FileStorageWalker.kt`) caches each directory's listing by mtime, so a repeat walk costs one `stat` per directory, one `stat` per book file, and a database diff. | Each foreground return and each return to the library | Grows with the number of directories on the phone, which can be thousands on a real phone (DCIM, Android/media, app folders). Part 2c/2d have the measured cost on the emulator's small storage. A check after unlocking the phone mid-book is the common case: the user glances at a notification and comes back. | Partly waste: a scan on every foreground return **into the reader** buys nothing for the open book. F-012/C-075 ask for it, so this is a spec question. |
| S2 | `FileWatch` (`app/.../library/FileWatch.kt:30-36`): an inotify `FileObserver` on the open book file | While a book is open | Kernel inotify: no CPU until the file changes. Its 1 s poll is T2. | Needed (R-008) |
| S3 | WorkManager: `SearchIndexWorker` only, idle + charging. No alarms, no services, no receivers, no foreground service, no wakelocks (no `WakeLock`, `AlarmManager` or `Service` in the code; manifest: one activity, the startup provider with WorkManager's initializer removed). | — | 0 on battery | — |
| S4 | WebView in the background: no `onPause()`/`pauseTimers()` on the WebView when the activity stops (`app/.../reader/ReaderEngine.kt` has none). Chromium hides the page (`document.hidden`), so rAF stops and timers are throttled, but a page counter still running when the user leaves keeps going on throttled timers (C1). The turn animation finishes at once when hidden (`paginator.js:155`, `:168`). | App in the background mid-count | Part 2d measures it | Small waste. `onPause` would stop the count, but the counter must restart on resume. |

### 7. Network

| # | Where | Verdict |
|---|-------|---------|
| N1 | No `INTERNET` permission in the manifest, so the app's sockets are refused by the kernel. The reader page's CSP (`reader-web/src/index.html:7`) allows only `'self'`, `blob:` and `data:`, and `'self'` is served in-process by `shouldInterceptRequest` (`ReaderEngine.kt`, `BookContentServer.kt`). The Play billing module is a stub (`app/src/play/.../BillingModule.kt`). | 0 bytes. Confirmed in Part 2. |

### 8. Sensors

| # | Where | When | Cost | Verdict |
|---|-------|------|------|---------|
| O1 | No sensor listeners in the app (no `SensorManager` or `OrientationEventListener`). The orientation lock uses `SCREEN_ORIENTATION_SENSOR_PORTRAIT`/`SENSOR_LANDSCAPE` (`app/.../reader/ReaderWindowState.kt:46-47`). These keep the system's accelerometer-based rotation listener on (to flip 180°) **even when the user has turned auto-rotate off**. `USER_PORTRAIT`/`USER_LANDSCAPE` would respect the user's rotation lock. | Only with an orientation lock chosen (the default `SYSTEM` adds nothing) | The accelerometer in batched low-power mode: about 0.1–1 mW | Small. Arguably needed: "either way up" is in the code comment (C-005). |

## Part 2: measurements on the emulator

### Setup

- Emulator `Medium_Phone` (API 37, user build, host GPU, 1080×2400, **60 Hz only**: supported render rates 60/30/20).
  Checked with `bench/emulator.sh check`. The device lock was taken and released by the scripts.
- `app-play-benchmark.apk` built from this branch (= v1.1-dev 00c65fe), with `pm clear`, all-files access granted, and
  one launch with its scan before measuring. Default settings (Light, Slide, keep-screen-on, fullscreen, Book pages).
- Battery set to "unplugged" (`dumpsys battery unplug`) so batterystats records.
- Book: `moby-dick-epub2-noimages.epub` (Gutenberg corpus). The page-counter cases also use `war-and-peace-epub3.epub`.
- Turns: `adb shell input swipe 900 1300 150 1300 120` (a real touch swipe; tap zones are off by default).
- CPU = utime+stime from `/proc/<pid>/stat` (ticks of 10 ms), before and after. "App" is the `app.fernreader` process,
  which includes WebView's in-process GPU and render threads. "Renderer" is the WebView sandboxed renderer
  `app.fernreader:com.google.android.webview:sandboxed_process0`.
- Frames: `dumpsys gfxinfo app.fernreader` (reset at the start of each scenario).
- Disk: `/proc/<pid>/io` cannot be read from the shell on a user build. So a second pass used `app-play-debug.apk`
  (same code, debuggable, installed over it with its data) and read `/proc/<pid>/io` and `/proc/<pid>/task/*/io` through
  `run-as`. Its CPU numbers are not used. Whole-device `/proc/diskstats` was also logged, but its background noise (3.7 MB per
  60 s with the app stopped) is larger than anything the app writes, so it is not used.
- Scripts: `tools/power/run.sh` (scenarios), `tools/power/threads.sh` (per-thread CPU and writes), `tools/power/power.sh`
  (helpers). Run with `bash`, not fish, after building `:app:assemblePlayBenchmark :app:assemblePlayDebug`.

### Results (benchmark build)

| Scenario | Length | App CPU | Renderer CPU | Frames | Wakelocks / alarms / jobs | Network |
|----------|--------|---------|--------------|--------|---------------------------|---------|
| **(a) Book open on a page, untouched** | 60 s | **0.30 s** (0.5 % of a core) | **0.00 s** | **0** | Screen-bright (WindowManager, keep-screen-on, attributed to the app) / 0 / 0 | 0 |
| **(b) 20 turns, one per 3 s** | 67 s | 6.07 s (0.30 s per turn) | 2.12 s (0.11 s per turn) | 213 (≈ 11 per turn) | same / 0 / 0 | 0 |
| **(c) Library, first 60 s after Back** | 60 s | 1.14 s | 0.02 s | 24 | none / 0 / 0. One scan: 47 ms walk, 60 files | 0 |
| **(c) Library idle, next 60 s** | 60 s | **0.20 s** | **0.00 s** | **0** | none / 0 / 0 | 0 |
| **(d) Background after reading (Home)** | 180 s | **0.28 s** | **0.06 s** | 0 | none / 0 / 0. batterystats: no partial wakelocks, jobs or alarms for the uid | 0 |
| (e) Back to the reader from Home | 30 s | 1.66 s | 0.18 s | 145 | — One foreground scan: 28 ms walk | 0 |
| Moby-Dick, first open, untouched (counter runs) | 60 s | 2.19 s | 2.43 s | 86 | — exact total (878) about 1 s after the first page | 0 |
| War and Peace, first open (counter runs) | 90 s | 4.17 s | 4.41 s | 91 | — exact total 2341 | 0 |
| War and Peace, text size +1 step (recount) | 60 s | 1.96 s | 4.62 s | 2 | — | 0 |
| War and Peace, reopened with the same settings (counted again) | 60 s | 2.88 s | 5.52 s | 95 | — same total (2774) counted again | 0 |

Network: the app has no `INTERNET` permission, `dumpsys netstats detail` has no rows for its uid, and no request ever
left the process.

### Disk writes (debug build, `/proc/<pid>/io`)

| Scenario | write_bytes (to storage) | Which thread |
|----------|--------------------------|--------------|
| Still page, 60 s | 40 KB | `Profile_Saver` (ART's profile, once; not app code) |
| 20 turns (per-thread pass: 10 turns) | 311 KB; **≈ 16 KB per turn** | `DefaultDispatcher` (the `PositionWriter` transaction) |
| Background, 180 s | 45 KB, once, on leaving | flushes on `onStop` and the ART profile |

`wchar` (all `write()` calls) jumped by about 31 MB during 20 turns. Per thread, 17.6 MB of it (in 10 turns) came from
`Chrome_InProcGpu` and 1.1 MB from `RenderThread`, with write_bytes 0: GPU command streams to the emulator's host GPU pipe. That is
an emulator artefact, not storage.

### (e) Where the idle CPU goes (per-thread `/proc/<pid>/task/*`)

| Situation | Thread | CPU / context switches |
|-----------|--------|------------------------|
| Still reader page, 60 s | `DefaultDispatcher` 0.22 s, `DefaultExecutor` 0.09 s, main 0.02 s | — |
| Still reader page, 20 s | `DefaultDispatcher` 356, `DefaultExecutor` 329, main 34, `Chrome_IOThread` 30, `file-watch` 25 | about 34 wakeups/s from coroutines |
| Library idle, 20 s | `DefaultDispatcher` 339, `DefaultExecutor` 325, main 30 | the same ticker |
| Background, 30–50 s after Home | `DefaultExecutor` 71, `DefaultDispatcher` 55 | still ticking until the freezer froze the process (`isFrozen=true` about 1.5 min after Home) |

`DefaultExecutor` is the kotlinx thread that runs `delay()` timers. The only repeating timer a grep of the Kotlin code finds (every other `delay` is a one-shot) is the
`sample(150)` in `LibraryRepository.libraryLive` (T1). The `file-watch` thread is FileWatch's 1 s poll (T2). The renderer
is completely idle on a still page (0 ticks in 60 s): no JS timer, rAF or observer runs (J1 confirmed).

### Idle numbers

- **Still reader page: 0 frames, renderer 0.00 s, app 0.30 s CPU per minute, all of it T1/T2 timers; 0 disk writes
  from app code; 0 network; no wakelock besides the screen's own.**
- **Library idle: 0 frames, app 0.20 s CPU per minute (the same ticker).**
- **Background: 0.28 s app plus 0.06 s renderer in 3 min, then frozen by the system. No wakelocks, alarms or jobs.**

### Not explained

- (e) Coming back to the reader from Home drew 145 frames in 30 s on a still page. The bars hide again (fullscreen) and
  the WebView is revealed one frame later (`ReaderSurface.kt`), but that does not obviously account for 145 frames. This
  was not traced (it would need a `dumpsys gfxinfo ... framestats` or Perfetto look). It is a one-off per return, not a
  steady cost.

### What the emulator cannot show

- **Display power**, the largest item: the emulator has no panel. Theme (OLED black vs light), brightness and refresh
  rate cannot be measured here.
- **120 Hz**: the emulator only has 60 Hz, so D3 can't be seen. `appRequest` in `dumpsys display` stays "0–Infinity"
  while reading, which shows the app asks for no rate.
- **CPU energy**: the emulator's cores and frequencies are the host's. Its CPU times are for comparing items, not for
  converting to mW. Per-turn app CPU is inflated by the emulator's GPU translation (the `Chrome_InProcGpu`/`RenderThread`
  work above). A phone does that on its GPU.
- **Radio**: none is used, and the app cannot open sockets.
- **Storage energy**: the size of an fsync on real flash.
- **Folder counts**: the emulator's shared storage is small. A scan on a real phone walks far more folders (S1).
- **The freezer**: on Android 14+ cached apps are frozen as here. On older or OEM-modified systems T1 may keep ticking in the
  background while the screen is on in another app. With the screen off and no wakelock, the CPU suspends anyway.

## Part 3: checking on a real phone (fish)

Connect with `tools/phone.sh connect <ip:port>`, or plug in USB. **Measure over wireless adb, not USB**: over USB the phone
charges, and the battery counters measure the charger, not the app. `dumpsys battery unplug` only makes batterystats
record. It does not stop the charging current.

### Per-app accounting (batterystats, Battery Historian)

```fish
adb shell dumpsys batterystats --reset
adb shell dumpsys batterystats --enable full-wake-history
# read for 30 min: a turn every 30–60 s, then leave the app in the background for 10 min
adb shell dumpsys batterystats app.fernreader > fern-batterystats.txt
grep -iE 'wake|job|alarm|cpu:|screen|Estimated power' fern-batterystats.txt
adb bugreport fern-bugreport.zip
```

Load `fern-bugreport.zip` into Battery Historian (`docker run -p 9999:9999 gcr.io/android-battery-historian/stable:3.1 --port 9999`,
then http://localhost:9999). It shows wakelocks, jobs, the CPU running state and screen brightness on one timeline.

### Real current per setting (fuel gauge)

Compare settings over equal stretches: same page, same brightness, about 15 min each. The difference in charge used is
what that setting costs.

```fish
adb shell settings put system screen_brightness_mode 0     # fixed brightness for the comparison
adb shell settings put system screen_brightness 128
function fern_charge; adb shell dumpsys battery | string match -r 'Charge counter: \d+'; end
fern_charge     # note the µAh, read on Light for 15 min, then:
fern_charge
# switch the reader theme to Black (Display panel), repeat; then 60 Hz vs 120 Hz (below)
adb shell settings put system screen_brightness_mode 1     # back to auto-brightness
```

### Refresh rate (D3)

```fish
adb shell dumpsys display | string match -r 'renderFrameRate [\d.]+|appRequest=.*'
adb shell dumpsys SurfaceFlinger | string match -ri 'refresh.?rate.*|vsync.?period.*' | head -5
```

Or turn on Developer options › "Show refresh rate" and look at the counter on a still page. 120 on a still page means D3
applies to this phone.

### Per-rail energy on a Pixel (display vs CPU), with Perfetto

```fish
printf '%s\n' 'buffers { size_kb: 65536 }' 'duration_ms: 300000' \
  'data_sources { config { name: "android.power" android_power_config { battery_poll_ms: 1000 collect_power_rails: true } } }' \
  'data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }' > fern-power.pbtxt
adb push fern-power.pbtxt /data/local/tmp/
adb shell perfetto --txt -c /data/local/tmp/fern-power.pbtxt -o /data/misc/perfetto-traces/fern.pftrace
adb pull /data/misc/perfetto-traces/fern.pftrace
```

Open `fern.pftrace` at https://ui.perfetto.dev. The power rails (display, CPU big/mid/little, GPU) show where the energy
goes on a still page, during a turn, and with each theme.

### Idle CPU and wakeups of the app on the phone (T1, T2)

```fish
set p (adb shell pidof app.fernreader)
adb shell "for t in /proc/$p/task/*; do echo \$(cat \$t/comm) \$(grep voluntary_ctxt \$t/status | head -1); done" > cs0.txt
# wait 20 s on a still page
adb shell "for t in /proc/$p/task/*; do echo \$(cat \$t/comm) \$(grep voluntary_ctxt \$t/status | head -1); done" > cs1.txt
paste cs0.txt cs1.txt
```

