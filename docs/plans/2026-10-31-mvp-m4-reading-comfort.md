# M4 — Reading comfort and page numbers: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make long reading comfortable: chapter boundaries turn without a blank page, a display panel inside the
reader (theme, font, size, spacing, margins, alignment, flow mode, brightness) previews live, scrolled mode runs
seamlessly across chapters, rotation keeps the passage, and a page indicator shows "page N", then "page N of ≈ M",
then the exact total.

**Architecture:** Most of the work is in the web reader. The vendored foliate-js paginator gets the S2 slot cache
(paged: hidden neighbour slots, instant swap; scrolled: slots stacked in reading order in one scroller). Reader
settings are a Kotlin `ReaderSettings` value stored in the `setting` table and sent with `setSettings`. TS injects
one stylesheet written against CSS custom properties and `data-er-*` attributes, so a change only touches
variables. A TS page counter (S1 batched hidden layout) sends per-section page counts. Kotlin's `PageTotals` turns
counts plus per-section text sizes (Kotlin `TextExtractor`, cached in a new `book_cache` table) into the indicator
state. A Compose display panel, themed by the reading theme, drives it all.

**Tech Stack:** as the master plan (Kotlin 2.4.20, AGP 9.1.0, Room KMP + bundled SQLite, Compose Multiplatform
1.10.0 + Material 3, TypeScript + Vite + Vitest, foliate-js 78914ae vendored). New assets only: three SIL OFL 1.1
font families (Literata, Source Sans 3, Atkinson Hyperlegible) with their licence texts.

**Spec:** `~/Projects/epub-reader-spec/` features.md F-031, F-034, F-037, F-070..F-075 (F-035 brightness via
F-075), F-081 (layout part); performance.md P-020, P-021, P-022, P-026 + earlier Must targets as regression;
clarifications C-002, C-003, C-004, C-009, C-015..C-017, C-022, C-024, C-028, C-029. Master plan §M4;
`docs/spikes/S1-page-totals.md`, `docs/spikes/S2-preloading.md`; parked core file Task 2.6;
`docs/milestones/M3.md` (C-009 fallback, Q3) and `M2.md` (system-bar strips).

## Global Constraints

Everything in the master plan's Global Constraints applies. In particular:
- Clean room: never open, list or search `~/Projects/epub-reader-dirty`; no proprietary reader as reference; our own wording and visual design.
- MIT; every dependency/asset MIT-compatible and listed in `docs/licenses.md`; fonts SIL OFL 1.1 with `OFL.txt` shipped next to them in the app assets (the M7 about screen reads `docs/licenses.md`); `fdroid` stays clean.
- `minSdk 26`, `compileSdk/targetSdk 37`, JVM 17, package root `app.epubreader`.
- R-005: text extraction and counting never on the main thread (Kotlin: `Dispatchers.IO`; JS counter yields between batches).
- Room schema change → version 4 with `AutoMigration(3, 4)`, schema exported; reading data never dropped.
- Positions crossing reader ↔ storage go through `PositionMap` (identity until M8).
- C-015: Must targets only; full bench at milestone end; `tools/smoke.sh` per build.
- C-024/C-028: `start` group only on a quiet host: `./gradlew --stop`, device session closed, `pgrep -a ffmpeg` empty.
- Device: hostId `local`, device `emulator-5554` (AVD Medium_Phone). UI via `$AD` from `device_open`; `adb` for build/install/push/logcat/`am start -W`/`am broadcast`/`dumpsys`/`settings`. Close `$AD` before instrumented tests. `connectedAndroidTest` uninstalls: `./gradlew :app:installPlayBenchmark` right before `bench/run_all.sh`.
- Commit per task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Decisions taken here (record in M4.md)

- **Scope of F-035:** the brightness slider and "follow system" are in the display panel (F-075 lists brightness).
  Fullscreen, keep-screen-on and orientation lock stay in M6 Task 6.2 (master plan). System bars stay visible
  in M4, so their strips take the reading theme's background.
- **Settings fields:** only the fields M4 uses (`fontScale`, `fontFamily`, `lineHeight`, `marginPercent`,
  `align`, `theme`, `flow`, `brightness`, `pageIndicatorScope`). The M6 input fields (`pageTurnAnimation`,
  `tapZones`, `volumeKeys`, `swipeDirection`) arrive with M6 Task 6.1. A missing field decodes to its default, so
  adding them later needs no migration.
- **Side margins** map to foliate's `gap` attribute: margin m % per side → `gap = 2m %`. Vertical margin stays
  32 px (the page indicator sits in the bottom one).
- **Neighbours with a different writing mode** (vertical/RTL next to horizontal) are not preloaded; their
  boundary uses foliate's normal load path. Same-mode neighbours (every corpus book) get the instant swap. This
  is exact by construction and avoids a second layout per view.
- **Scrolled stacking** only for horizontal writing. Vertical-writing books in scrolled mode keep foliate's
  per-section scroller (rare; F-081 check books are read paged).
- **"page N" before any estimate (C-017):** N comes from the same text model, calibrated on the visible section
  alone (its exact page count and its character count). After the stratified sample, N and ≈ M use the sample
  calibration; after the exact count, both are exact. Before section text sizes exist (first open of a book, ≈ the
  first second), no number is shown.
- **Counter start:** the web side starts a count by itself after the first paint and after every layout change
  (settings except theme, viewport size, flow), debounced 150 ms; each count has a generation number.

## Review Focus

1. **Saved position that no longer resolves** (file edited in place, C-009/C-029): expected nearest valid
   position in the same chapter, never the book start. Pinned: Task 1 `PositionFallbackTest` (CFI past the end of
   its chapter → last page of that chapter) and Vitest `clampCfi.test.ts`.
2. **Settings changed while a count runs or while a neighbour is loading** (slider drags, rotation during a
   count): expected the stale count is dropped, the hidden neighbour gets the new styles, the next swap shows the
   new layout. Pinned: Task 7 `PageTotalsTest.stale_generation_is_ignored`, Task 2 `ReaderBoundaryTest.settings_change_reaches_preloaded_neighbour`.
3. **Highlights / search hits drawn while their section is preloaded** (S2 bug). Expected visible after the
   swap. Pinned: Task 2 `ReaderBoundaryTest.decoration_in_preloaded_section_survives_swap`.
4. **A stored settings value from a newer/older app or hand-edited** (unknown field, out-of-range number, unknown
   enum). Expected: decoded with defaults and clamped, never a crash. Pinned: Task 3 `ReaderSettingsTest.decodes_garbage_to_defaults`.
5. **Book whose Kotlin spine differs from foliate's sections** (count mismatch, failed package parse). Expected:
   no estimate (the indicator waits for exact counts), no crash, no wrong numbers. Pinned: Task 7
   `PageTotalsTest.stats_size_mismatch_disables_estimate`.

---

## File structure

```
reader-web/vendor/foliate-js/paginator.js   slot cache (paged swap, scrolled stack), pagedLayout, busy, refresh   (2, 5)
reader-web/vendor/foliate-js/PATCHES.md      patch log                                                            (2, 5)
reader-web/src/reader/position.ts            resolveSaved / clampToRange (C-009)                                   (1)
reader-web/src/reader/styles.ts              readerCss(), applySettings(doc, s), WebSettings type                  (3)
reader-web/src/reader/counter.ts             stratified sample, grouping, batched + lane counting                  (8, 9)
reader-web/src/reader/reader.ts              settings, relocate page fields, counter wiring, test hooks            (1-9)
reader-web/src/public/fonts/*.ttf, OFL-*.txt bundled fonts (copied to assets/reader/fonts)                         (4)
ui/.../reader/bridge/Protocol.kt             SetSettings, GoToFraction, PageCounts, relocate fields                (3, 8)
core/data/.../ReaderSettings.kt              model, clamp, JSON codec; SettingsStore key                           (3)
core/data/.../BookCacheEntity.kt, BookCacheDao.kt, AppDatabase v4                                                   (7)
core/epub/.../text/TextExtractor.kt          parked Task 2.6                                                       (7)
ui/.../reader/PageTotals.kt                  estimator + indicator state                                           (8)
ui/.../reader/display/DisplayPanel.kt, ReadingTheme.kt                                                              (4)
ui/.../reader/ReaderController.kt, ReaderScreen.kt                                                                  (3-9)
app/.../reader/SectionStatsLoader.kt         spine stats from the open session (IO)                                (7)
app/.../MainActivity.kt                      brightness, system bars, orientation → controller                     (4, 6)
app/src/androidTest/.../reader/*Test.kt      instrumented tests                                                    (all)
bench/groups/action.sh, bench/groups/pagetotal.sh                                                                  (10)
```

## Tasks

Execution order: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10. (Task numbers here; master plan ids in brackets.)

### Task 1: C-009 nearest valid position (C-029, M3 Q3)

**Files:** Create `reader-web/src/reader/position.ts`, `reader-web/test/position.test.ts`; modify `reader.ts`
(`open`, `reopen`, `goTo` for CFI targets); create `app/src/androidTest/.../reader/PositionFallbackTest.kt`.

**Interfaces:** Produces `resolveSaved(book, cfi): {index, anchor: (doc) => Range} | null` and
`clampToRange(doc, steps): Range` (steps = the content path after the `!` of the start of the CFI).

- [ ] Vitest (jsdom): body with 3 `<p>`; `clampToRange` for `/4/2/1:3` = exact; `/4/40` (past the end) → a range on
  the last character of the last non-blank text node; `/4/4/1:9999` → end of the second paragraph's text; `/99` →
  last text of the document. Run → FAIL (module missing).
- [ ] Implement: `resolveSaved` parses with foliate `CFI.parse`, takes the spine step via `book.resolveCFI` (index),
  anchor = `doc => { try { r = CFI.toRange(doc, parts); if (r.startContainer.isConnected) return r } catch {}; return clampToRange(doc, steps) }`.
  `clampToRange` walks even steps as element children (`k = index/2 − 1`), stops at the first step that does not
  resolve, and returns the range over the last non-blank character inside the deepest resolved node if the
  failing step points past its children (or the node is the last resolved one), else its first character.
  `reader.ts` uses it for the `open` position, warm reopen moves and `goTo` targets that are CFIs; if the index
  itself does not resolve, the old fallback (book start) stays.
- [ ] Vitest → PASS.
- [ ] Instrumented `PositionFallbackTest`: open War and Peace, `goTo` a chapter (TOC "BOOK ONE: 1805" + 3 `next`),
  take the relocate CFI and sectionIndex, close; rewrite the stored position with the content path replaced by
  `/4/9999` (past the end, same spine step) through `PositionDao`; reopen → first relocate has the same sectionIndex
  and `page == pages` (last page of that chapter, fields from Task 3; until Task 3 assert `fraction > 0.9` of the section via a test hook).
- [ ] Commit `reader: saved positions that no longer resolve land on the nearest valid place in their chapter (C-009)`.

### Task 2 [4.1]: Neighbour preloading, paged (P-026, F-031 basis)

**Files:** Modify `reader-web/vendor/foliate-js/paginator.js`, `PATCHES.md`; `reader.ts` (`preload` attribute,
test hooks); create `app/src/androidTest/.../reader/ReaderBoundaryTest.kt`.

- [ ] Port the spike patch (`diff -u reader-web/vendor/foliate-js/paginator.js spikes/foliate-spike/app/src/main/assets/foliate-js/paginator.js`)
  items 1-7 of S2 §"The patch", with these changes:
  - `#preloadIndex` passes `beforeRender = ({vertical, rtl}) => vertical === this.#vertical && rtl === this.#rtl ? this.#lastLayout : (entry.mismatch = true, this.#lastLayout)`;
    a `mismatch` entry is evicted after load and never swapped (decision above).
  - Export `View` (needed by the counter lane, Task 9) and add `get layout()`/`get styles()`.
  - Add `get busy()` = `#locked` (counter waits while a turn runs).
  - Log every change in `PATCHES.md` (date, file, change, reason, commit).
- [ ] `reader.ts`: `r.setAttribute('preload', '')`. Test hooks on `window.__readerTest` (benchmark/debug use only; read-only
  plus `addTestDecoration(cfi)` → `view.addAnnotation({value: cfi})` with a `draw-annotation` handler that draws
  `Overlayer.highlight`; `overlayCount(index)` returns the number of SVG children of that section's overlayer from
  `renderer.getContents()`; `cachedIndices()`).
- [ ] Instrumented `ReaderBoundaryTest` (W&P):
  - `ten_boundaries_in_order`: from "BOOK ONE: 1805", send `next` until 10 sectionIndex increments; each increment is +1 and
    a `painted` follows each relocate.
  - `decoration_in_preloaded_section_survives_swap`: at the last page of a chapter, the next section is cached;
    `addTestDecoration(<cfi of the next section's first paragraph>)`; `overlayCount(next) > 0` before the turn; after `next`,
    sectionIndex = next and `overlayCount(next) > 0`.
  - `settings_change_reaches_preloaded_neighbour` (enabled in Task 3): change font scale while at a chapter's last page,
    turn → the new section's `documentElement` has the new `--er-font-scale`.
- [ ] `./gradlew :app:connectedPlayDebugAndroidTest -P...class=app.epubreader.reader.ReaderBoundaryTest` → PASS; vitest + `./gradlew check` green.
- [ ] Commit `reader: preload neighbour sections; boundary turns swap instantly (P-026)`.

### Task 3 [4.2]: Reader settings model, CSS, `setSettings` (F-070, F-071, F-072, F-074, P-020)

**Files:** Create `core/data/.../ReaderSettings.kt`, `core/data/src/jvmTest/.../ReaderSettingsTest.kt`,
`reader-web/src/reader/styles.ts`, `reader-web/test/styles.test.ts`, `bridge/fixtures/setSettings.json`; modify
`Protocol.kt`, `protocol.ts`, `bridge.ts` (shape check), `reader.ts`, `ReaderController.kt`,
`docs/bridge-protocol.md`; instrumented `ReflowTest.kt`.

**Interfaces:**
```kotlin
@Serializable data class ReaderSettings(
    val fontScale: Double = 1.0,            // 0.8..2.6, step 0.1
    val fontFamily: String = PUBLISHER,     // "publisher" | "literata" | "source-sans" | "atkinson"
    val lineHeight: Double = 1.5,           // 1.3..2.0, step 0.1
    val marginPercent: Int = 5,             // 0..10 per side
    val align: Align = Align.PUBLISHER,     // PUBLISHER | START | JUSTIFY
    val theme: ReadingThemeId = ReadingThemeId.LIGHT, // LIGHT | DARK | BLACK | WARM
    val flow: Flow = Flow.PAGED,            // PAGED | SCROLLED | AUTO
    val brightness: Double? = null,         // null = follow system; else 0.05..1.0
    val pageIndicatorScope: IndicatorScope = IndicatorScope.BOOK, // BOOK | CHAPTER
) { fun clamped(): ReaderSettings; fun stepFont(delta: Int): ReaderSettings; companion object { fun decode(json: String?): ReaderSettings; const val KEY = "reader.settings" } }
fun ReaderSettings.encode(): String
```
Bridge: `ToWeb.SetSettings(settings: WebSettings)`, `WebSettings(fontScale, fontFamily, lineHeight, marginPercent,
align: "publisher"|"start"|"justify", theme: "light"|"dark"|"black"|"warm", flow: "paginated"|"scrolled")` (flow
already resolved: AUTO → by orientation, C-004). `ToWeb.Open` gains `settings: WebSettings`. Relocate gains
`page: Int` (1-based text page in the section, paged; 0 in scrolled), `pages: Int` (text pages of the section, paged;
0 in scrolled), `sectionFraction: Double`.

- [ ] `ReaderSettingsTest`: clamp of each numeric bound and rounding to the step (`1.2499 → 1.2`, `3.0 → 2.6`,
  `0.7 → 0.8`, margin `-1 → 0`, `11 → 10`); `stepFont(+1)` from 2.6 stays 2.6; round trip; `decodes_garbage_to_defaults`
  (`null`, `"{"`, `{"fontScale":"x"}`, `{"theme":"neon","fontScale":9,"extra":1}` → defaults/clamped, never throws).
  Run → FAIL.
- [ ] Implement (lenient `Json { ignoreUnknownKeys = true; coerceInputValues = true }`, per-field fallback by
  decoding to `JsonObject` first and reading each field with `runCatching`).
- [ ] `styles.test.ts`: `readerCss()` contains the `--er-*` rules and the four `@font-face` blocks
  (`url(../fonts/…)` resolved against the host page, so `https://appassets…/assets/reader/fonts/…`); `applySettings(doc, s)` sets
  `--er-font-scale`, `--er-line-height`, `data-er-theme`, `data-er-align`, `data-er-font` on `documentElement`
  and never touches `<style>` text (snapshot per setting: before/after `outerHTML` differ only in the `html` attributes).
- [ ] Implement `styles.ts`:
  - `html { font-size: calc(100% * var(--er-font-scale, 1)) !important }`.
  - `html[data-er-lh] body, html[data-er-lh] :is(p, li, blockquote, dd, dt) { line-height: var(--er-line-height) !important }`.
  - `html[data-er-align="start"] body :is(p, li, blockquote, dd) { text-align: start !important }` and `justify` likewise.
  - `html[data-er-font] body, html[data-er-font] body *:not(code, pre, kbd, samp, tt, code *, pre *) { font-family: var(--er-font) !important }`.
  - Themes: `html[data-er-theme] { background: var(--er-bg) !important; color: var(--er-fg) !important; color-scheme: var(--er-scheme) }`;
    for dark/black/warm also `body *:not(img, svg, video) { color: inherit !important; background-color: transparent !important; border-color: var(--er-rule) !important }`
    and `a:any-link { color: var(--er-link) !important }`. Theme colours: Task 4 palette (one table in `styles.ts`, mirrored in `ReadingTheme.kt`).
  - `reader.ts`: `renderer.setStyles(readerCss())` once per view; on `load` of any section doc call
    `applySettings(doc, current)`; on `setSettings`: apply to every doc of `renderer.getContents()` (current +
    preloaded), set the `gap` attribute if the margin changed, the `flow` attribute if it changed, set the host page
    `body` background, then the View's ResizeObserver re-expands and re-anchors (first visible range kept).
- [ ] Kotlin: `ReaderController` gets `settings: StateFlow<ReaderSettings>` and `fun updateSettings(f: (ReaderSettings) -> ReaderSettings)`
  (clamps, sends `SetSettings`, stores through `SettingsPort` debounced 300 ms, probe `layout_change`). Loaded from
  `SettingsStore` at start; `Open` carries it.
- [ ] Instrumented `ReflowTest.each_font_step_keeps_first_paragraph`: W&P mid chapter; hook `__readerTest.firstVisibleBlock()`
  returns the text of the block element containing the start of the visible range; for each step 0.8→2.6→0.8 the
  original block still intersects the visible range (`__readerTest.blockVisible(text)`).
- [ ] Fixtures + `ProtocolFixtureTest`/`protocol.test.ts` for `setSettings` and the new relocate fields; all green.
- [ ] Commit `reader: settings model and live CSS variables (F-070..F-074)`.

### Task 4 [4.3]: Display panel, themes, fonts, brightness, system bars (F-075, F-071, F-074)

**Files:** Create `ui/.../reader/display/ReadingTheme.kt`, `DisplayPanel.kt`, `ui/src/jvmTest/.../ReadingThemeTest.kt`;
fonts in `reader-web/src/public/fonts/`; modify `ReaderScreen.kt`, `App.kt`, `MainActivity.kt`, `ReaderSurface.kt`,
`Copy.kt`, `docs/licenses.md`.

**Palette (our own):**

| Theme | Background | Text | Link | Rule | UI scheme |
|---|---|---|---|---|---|
| Light (default) | `#FBFAF7` | `#1F1D1A` | `#2F5D50` | `#D8D4CC` | light, primary `#2F5D50` |
| Dark (dark grey) | `#212427` | `#DAD6CE` | `#8CC7B4` | `#3A3E42` | dark, primary `#8CC7B4` |
| Black (OLED) | `#000000` | `#C4C0B8` | `#7FB8A6` | `#2A2A2A` | dark, surface `#0E0E0E` |
| Warm | `#F4E9D3` | `#3A2E21` | `#7A4E1E` | `#D9C7A6` | light, primary `#7A4E1E` |

Fonts (SIL OFL 1.1, Regular/Italic/Bold/BoldItalic or variable roman+italic): Literata (serif, default
reading face), Source Sans 3 (sans), Atkinson Hyperlegible (high legibility), plus "Publisher". Served at
`/assets/reader/fonts/*`, previewed in Compose from the same asset files (`Font(path, assets)` in `app`, passed to
`ui` as `Map<String, FontFamily>`), so they ship once.

- [ ] `ReadingThemeTest`: every theme's text/background contrast ≥ 7:1 (WCAG AAA body text), link ≥ 4.5:1; the four ids map to distinct schemes. Run → FAIL, implement `ReadingTheme` (colours + `colorScheme()`), PASS.
- [ ] Fonts: download from the upstream repos (google/fonts `ofl/literata`, `ofl/sourcesans3`, `ofl/atkinsonhyperlegible`) into `reader-web/src/public/fonts/` with each `OFL.txt`; record name, version, URL, licence in `docs/licenses.md`.
- [ ] `DisplayPanel` (Material 3, bottom-anchored `Surface` over the page, not a modal sheet: the top of the page
  stays visible and previews live; scrim-free; closes on Back or outside tap through the chrome). Rows:
  theme swatches (four 48 dp circles, page colour with an "Aa" in the text colour, selected ring in primary);
  text size `−` / percentage / `+`; font chips, each label in its own face; line spacing slider 1.3–2.0 (8 stops);
  margins slider 0–10 %; alignment segmented buttons (Publisher / Left / Justified); flow segmented buttons
  (Pages / Scroll / Auto); brightness slider + "System" switch. Every change calls `controller.updateSettings`.
  Copy in `Copy.kt` (our wording).
- [ ] Reader chrome (top bar, panel, TOC panel) uses `MaterialTheme(readingTheme.colorScheme())`; the library keeps the app theme.
- [ ] System bars on the reader route: the strips behind the inset WebView are painted with the theme background
  (`Box(Modifier.background(bg))` around `ReaderSurface`; WebView `setBackgroundColor(bg)`), and
  `WindowCompat.getInsetsController(...).isAppearanceLightStatusBars/NavigationBars = theme.isLight`; restored on leaving the reader.
- [ ] Brightness: `window.attributes.screenBrightness = brightness ?: BRIGHTNESS_OVERRIDE_NONE` while the reader is on top; reset on leaving.
- [ ] By hand on the emulator (`$AD`): each theme recolours instantly with the CFI unchanged (relocate count unchanged, F-074 check); screenshots `docs/milestones/M4-theme-{light,dark,black,warm}.png`, `M4-panel.png`.
- [ ] Commit `reader: display panel, reading themes, bundled OFL fonts, brightness (F-075)`.

### Task 5 [4.4]: Flow modes and seamless scrolling (F-031, C-004)

**Files:** Modify `paginator.js` (+ `PATCHES.md`), `reader.ts`, `ReaderController.kt`; instrumented `ScrolledFlowTest.kt`.

- [ ] Paginator, scrolled + `preload` + horizontal writing ("stack mode"):
  - All slots in flow, in index order in `#container` (`#slotStyle` → `''`; inserted before the first slot with a larger index); container `overflow-anchor: none`.
  - `#slotOffset()` = current slot's `offsetTop` in stack mode, else 0. `get start()` subtracts it; `#scrollTo(offset)` adds it. So every existing per-section computation (visible range, fraction, anchors) stays relative to the current section.
  - A non-debounced `scroll` listener: the slot containing `scrollTop + 1` becomes current (`#view`, `#index`, background) without scrolling, then `#afterSwap()` (preload ±1, evict others).
  - Stack anchoring: before any insert/evict/expand of another slot, remember the current slot's `offsetTop`; afterwards add the difference to `scrollTop` (`#fixStack()`; called from `#newEntry`, `#evict`, and the `onExpand` of non-current views).
  - `#scrollNext`/`#scrollPrev` at a section edge swap to the cached neighbour (`#goTo` → `#swapTo`), which in stack mode only changes `#view/#index` and scrolls by the remaining distance: content continues.
  - Switching `flow` evicts every non-current slot, re-renders the current view, then `#afterSwap()` preloads with the new layout.
- [ ] `reader.ts`: `flow` from settings; relocate `page/pages` = 0 in scrolled.
- [ ] Kotlin: effective flow = `AUTO → if (landscape) SCROLLED else PAGED` (C-004); `controller.setLandscape(Boolean)` from `ReaderScreen` (`LocalConfiguration`) re-sends settings when the effective flow changes.
- [ ] Instrumented `ScrolledFlowTest` (W&P, scrolled):
  - `fling_past_chapter_end_shows_next_heading`: go to the last screen of a chapter, one `swipe` fling up (`UiDevice.swipe` short duration) → within 2 s the relocate sectionIndex = next and `__readerTest.visibleText()` contains the next chapter's first heading text, no second gesture.
  - `cfi_round_trip_at_slot_boundary`: goTo the CFI reported right after crossing → same sectionIndex and CFI.
  - `no_jump_when_prepending`: scroll up across a boundary; the first visible block text before and after the previous section is prepended is the same.
- [ ] Commit `reader: scrolled mode continues across chapters; auto flow (F-031, C-004)`.

### Task 6 [4.5]: Rotation keeps the position (P-022, F-037)

**Files:** Modify `reader.ts` (re-anchor after resize), `MainActivity.kt` (probe `config_changed`); instrumented `RotationTest.kt`.

- [ ] `RotationTest` (W&P mid-chapter, paged): rotate to landscape and back (`UiDevice.setOrientationLeft/Natural`) → final relocate CFI equals the starting CFI; repeat with `flow = AUTO` (landscape is scrolled) → same CFI back in portrait. Run → see what fails.
- [ ] Fix what the test shows (expected: the paginator's ResizeObserver re-renders and re-anchors; cached slots re-render; the anchor must survive the flow switch).
- [ ] Commit `reader: rotation keeps the passage (P-022, F-037)`.

### Task 7 [4.6]: Canonical text, section stats, `book_cache` (F-034 estimate input, Review Focus 1 of the master plan)

**Files:** Create `core/epub/.../text/TextExtractor.kt` + test (parked file Task 2.6, code as written there);
`core/data/.../BookCacheEntity.kt`, `BookCacheDao.kt`, `AppDatabase` v4 + `AutoMigration(3, 4)`, schema `4.json`,
`MigrationTest.v3_to_v4_keeps_everything`; `app/.../reader/SectionStatsLoader.kt`; modify `BookOpenerPort`,
`BookOpener`, `LibraryRepository`, `ReaderController`.

**Interfaces:**
```kotlin
data class SectionStat(val chars: Int, val images: Int)          // ui/reader (commonMain)
interface BookOpenerPort { …; suspend fun sectionStats(sessionId: String): List<SectionStat>? }   // null = package unreadable
@Entity(tableName = "book_cache", primaryKeys = ["bookId", "kind"]) data class BookCacheEntity(val bookId: String, val kind: String, val digest: String, val json: String)
LibraryRepository.cached(bookId, kind, digest): String?;  LibraryRepository.storeCache(BookCacheEntity)
```
- `chars` = length of the canonical text with whitespace runs counted as one character; `images` = `imageCount`.
  One entry per spine item (all items, linear or not, in spine order; a missing file → `SectionStat(0, 0)`).
- Cache kind `section-stats.v1`, digest = the session's central-directory digest (a changed file recomputes).

- [ ] TextExtractorTest from the parked file → FAIL → implement → PASS (record the 10 MB extraction time in the commit body).
- [ ] MigrationTest v3→v4 → FAIL → entity/DAO/version → PASS.
- [ ] `SectionStatsLoader(session)` on `Dispatchers.IO`: `PackageLoader.load(zip)` → for each spine item `TextDecoding.decode(readBytes)` → `TextExtractor.extract` → stat. Host test in `app/src/test` on a generated two-chapter zip: stats size = spine size, chars of `<p>a  b</p>` = 3.
- [ ] Controller: after `painted_first` and a known bookId → cached stats or loader → store → `PageTotals.setStats` (Task 8). Probe `stats_ready`.
- [ ] Commit `core: canonical text extraction; per-section stats cached per book (book_cache v4)`.

### Task 8 [4.7]: Page indicator "page N" → "page N of ≈ M" (F-034, C-002, C-016, C-017)

**Files:** Create `reader-web/src/reader/counter.ts` (sample batch only here), `reader-web/test/counter.test.ts`,
`ui/.../reader/PageTotals.kt`, `ui/src/jvmTest/.../PageTotalsTest.kt`; modify protocol (both sides + fixtures),
`reader.ts`, `ReaderController.kt`, `ReaderScreen.kt`; instrumented `PageIndicatorTest.kt`.

**Interfaces:**
```ts
// FromWeb
{ type: 'pageCounts', sessionId, gen: number, linear: number[] /* only in the first message of a gen */,
  counts: [index: number, pages: number][], sample: boolean, done: boolean }
// ToWeb
{ type: 'goToFraction', fraction: number }
```
```kotlin
data class PageIndicator(val page: Int?, val total: Int?, val exact: Boolean, val chapterScope: Boolean)
class PageTotals {
    fun setStats(stats: List<SectionStat>?, sections: Int)   // null or size mismatch → estimates disabled
    fun onCounts(m: FromWeb.PageCounts)                     // a newer gen resets; an older gen is ignored
    fun onRelocate(m: FromWeb.Relocate)
    val state: StateFlow<PageIndicator>
}
```
Model (S1): sample sections with `chars ≥ 200`: `C = Σchars / Σ(pages − 0.5)`. Estimated pages of an uncounted
linear section: `chars ≥ 200 → max(1, round(chars / C + 0.5))`, else `max(1, images)`. Counted sections use
their count; the visible section uses `relocate.pages` (paged; C-003: a difference from its count is logged as
probe `count_mismatch`). Before the sample: `C` from the visible section alone (if its `chars ≥ 200`), total
hidden. `page = Σ pages of linear sections before the current + relocate.page` (scrolled, C-002:
`floor(sectionFraction × pagesOf(current)) + 1`). `total` = Σ over linear sections; `exact` when `done`. Chapter
scope: `page/pages` of the visible section (scrolled: from its count). `page` is clamped to `1..total`.

- [ ] `PageTotalsTest`: calibration math on a 4-section fixture; image-only section → `images` pages; `stale_generation_is_ignored`; `stats_size_mismatch_disables_estimate`; scrolled page from `sectionFraction`; exact total once `done`; chapter scope. Run → FAIL → implement → PASS.
- [ ] `counter.test.ts`: `stratifiedSample(linear, 12)` = 12 distinct indices spread over the book (first > 0 unless n ≤ 12, deterministic), and `[...sample, ...rest]` covers every linear index once.
- [ ] `counter.ts` (sample only): batched hidden layout exactly as `spikes/foliate-spike/app/src/main/assets/host/count.js` `batched`, rewritten in TS, with `layout = renderer.pagedLayout` (new paginator getter: the paged layout computed from a hidden `#probe` element placed like the paged `#container`, so scrolled mode counts paged pages too) and `styles = renderer.styles` + `applySettings` on the batch document; awaits `fonts.ready` and images; waits while `renderer.busy`; aborted by a newer gen (checked between awaits).
- [ ] `reader.ts`: after the first `painted` and after each layout change (debounced 150 ms) start a gen; send the sample `pageCounts` (`sample: true`, `done: false`).
- [ ] UI: page indicator label in the bottom margin, always visible (12 sp, text colour at 60 %): `"Page 37"`, `"Page 37 of ≈ 2,341"`, `"Page 37 of 2,341"`; chapter scope `"Page 3 of 12 in chapter"`. Chrome bottom bar: the same label + a `Slider` (book fraction) that sends `goToFraction` on release, and the scope toggle lives in the display panel.
- [ ] Instrumented `PageIndicatorTest` (W&P, font +10 %): a page number without total at once (≤ 1 s, stats cached from a previous open in the test), "≈" after the sample (≤ 2 s), and (after Task 9) the estimate within ±5 % of the exact total.
- [ ] Commit `reader: page indicator with page N and an estimated total (F-034, C-017)`.

### Task 9 [4.8]: Exact page total (P-021, C-003, C-016)

**Files:** Modify `counter.ts`, `counter.test.ts`, `reader.ts`; instrumented `PageCountAgreementTest.kt`.

- [ ] `counter.test.ts`: `planBatches(docsMeta, 24)` groups consecutive sections only when their head style-sheet set (`outerHTML` of `link[rel~=stylesheet]`/`style` in `head`) is equal, `body` has no attributes, and the writing mode equals the book's; others become single-section lane jobs. Run → FAIL → implement → PASS.
- [ ] After the sample: 24-section batches in reading order; lane jobs counted in a foliate `View` (exported in Task 2) inside a hidden host with `renderer.pagedLayout` (`pages = round(width / pageWidth) − 2`, as in the spike). Yield (`setTimeout 0`) between batches; wait while `renderer.busy`. Final message `done: true`. Probe `page_total_exact` (Kotlin, when `PageTotals` becomes exact) with value and gen; `page_total_estimate` with the estimate.
- [ ] `PageCountAgreementTest` (C-003): W&P at font 1.0 and 1.4: after `done`, for every 8th linear section `goTo` it and compare `relocate.pages` with the counted value: all equal.
- [ ] `PageIndicatorTest` estimate clause enabled: |estimate − exact| / exact ≤ 5 %.
- [ ] Commit `reader: exact page total by batched hidden layout (P-021, C-003)`.

### Task 10 [4.9]: Milestone suite, review, notes

**Files:** Modify `bench/groups/action.sh`, `bench/README.md`, `app/src/main/.../MainActivity.kt` (bench commands
`fontStep`, `rotate` helpers stay in the shell); create `bench/groups/pagetotal.sh`, `docs/milestones/M4.md`.

- [ ] Bench commands (benchmark build): `fontStep ±1`, `setFlow paged|scrolled`, `lastPage` (goTo the last page of the current section).
- [ ] `action.sh` adds:
  - P-020: W&P, panel open (`display` command), `fontStep +1`; value = recording (spec method, `summarize_open.sh`) with the event delta `cmd → painted` in the note; if the recording is NA (small change), the event delta plus 50 ms (as C-025 for P-030) and the deviation is listed in M4.md. 3 runs.
  - P-022: `settings put system accelerometer_rotation 0; settings put system user_rotation 1`; recording from the command to the last large change; 3 runs, rotating back between runs.
  - P-026: 5 boundary turns × 3 runs with `tools/bench_action.sh`-style recording and `tools/blank_frames.py` (S2 rule); emits `P-026` (median per run) and `P-026.blank_frames`.
- [ ] `pagetotal.sh`: W&P open 6 s, `fontStep +1`, wait for `page_total_exact`; `P-021` = `cmd → page_total_exact`; `P-021.estimate_error_pct` from the two events. 3 runs alternating +1/−1.
- [ ] Full suite on the final code: `./gradlew --stop`, close `$AD`, `pgrep -a ffmpeg` empty, `./gradlew :app:installPlayBenchmark`, `bench/run_all.sh start`; then open `$AD` for `open action pagetotal mem scan`. Re-run a failing group once.
- [ ] Whole-branch review (superpowers:requesting-code-review); fix Critical/Important with pinning tests.
- [ ] Full instrumented suite on the final code; `./gradlew check`; vitest.
- [ ] `docs/milestones/M4.md`: results table, screenshots, review findings and fixes, deviations, questions for the gatekeeper, how to try it. Install the debug APK, leave the emulator on the library.
- [ ] Commit `docs: M4 milestone results`.

## Self-review notes
- Coverage: F-031 (5), F-034 (8, 9), F-035 brightness (4; rest M6 by decision), F-037 (1, 6), F-070 (3), F-071 (3, 4), F-072 (3), F-074 (3, 4), F-075 (4), F-081 layout part (2, 5 decisions), P-020 (3, 10), P-021 (8, 9, 10), P-022 (6, 10), P-026 (2, 10), C-009/C-029 (1), M2 system-bar note (4).
- Types: `WebSettings`, `PageCounts`, `SectionStat`, `PageTotals`, `ReaderSettings` names are used consistently in Tasks 3-9.
