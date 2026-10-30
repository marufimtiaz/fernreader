# EPUB rendering engine evaluation

Date: 2026-10-30. Author: implementation side (clean room). Raw data: `results/` (videos/screenshots under
`results/raw/`, not committed).

## 1. Candidates, versions, licenses

| Engine | Version used | License | Bundled / transitive pieces (license) | MIT-compatible? |
|---|---|---|---|---|
| Readium Kotlin Toolkit (`readium-shared`, `-streamer`, `-navigator`) | 3.4.0 (released 2026-09-11, Maven Central) | BSD-3-Clause | ReadiumCSS (BSD-3), OpenDyslexic font asset (SIL OFL 1.1), jsoup 1.23.2 (MIT), Guava 33.3.1 (Apache-2.0), Timber 5.0.1 (Apache-2.0), koi-core 0.5.5 (Apache-2.0), AndroidX/Kotlin (Apache-2.0) | Yes. BSD-3 needs its notice in "about/licenses"; OFL font may be bundled but stays OFL (or excluded) |
| foliate-js | git `78914ae` (2026-05-01; the project publishes no releases or tags) | MIT | Only first-party modules used (view, epub, epubcfi, paginator, fixed-layout, progress, overlayer, search, text-walker). Not shipped: `vendor/zip.js` (BSD-3), `vendor/fflate` (MIT), `vendor/pdfjs` (Apache-2.0) | Yes |

No copyleft in either dependency tree.

Toolchain: AGP 9.1.0, Gradle 9.3.1, Kotlin 2.4.20 (Readium 3.4.0 needs a Kotlin 2.4 compiler and core-library
desugaring), compile/target SDK 37, min SDK 26. System WebView on the emulator: 158.0.8127.2.

## 2. Prototypes

Both prototypes share one Kotlin reader shell (`prototypes/common`): a library list, a reader with a floating bar
(TOC, search, text size −/+, page label), and a debug command receiver used by the drivers in `tools/`.
Paged mode, swipe to turn, text size, TOC jump, search and rotation (activity handles the configuration
change itself; no re-creation) work in both.

- `prototypes/readium`: `EpubNavigatorFragment` from Readium 3.4.0 with default configuration. Search uses
  Readium's `StringSearchService` (ICU, case- and diacritic-insensitive options).
- `prototypes/foliate`: foliate-js `<foliate-view>` in a WebView. A Kotlin `WebViewAssetLoader` path handler serves
  zip entries straight from `java.util.zip.ZipFile` (random access, no copy of the book into JS memory); the
  page CSP (`script-src 'self'`) is inherited by the blob: section frames, so book scripts do not run.
  Search is foliate's (Intl.Collator, base sensitivity: case- and diacritic-insensitive), capped at 5000 hits.

## 3. Method

Emulator: AVD Medium_Phone, API 37, 1080×2400 @ 420 dpi, 16 KB pages, 4 vCPU, 4 GB guest RAM (matches
performance.md). Corpus built with the spec's tools on 2026-10-30 and pushed to `/sdcard/Books`.
All targets are medians of 3 runs unless stated.

- **Open times (P-010..P-016):** `tools/bench_open.sh` — fresh process, library visible (WebView pre-warmed
  while the library is shown, as a real app would), screen recorded through agent-device with touches hidden,
  tap the book, analyse with the spec's frame-diff rule. Reopen = Back and tap the same book again in the same
  process. `tools/split_open.sh` splits the result into *shell* time (tap → reader frame visible) and *engine*
  time (reader frame → page painted). `tools/bench_inplace.sh` measures the same with the book list shown inside
  the reader activity and the engine prepared behind it ("in-place"), which removes the activity transition.
- **In-reader actions (P-020, P-022, P-023, P-026, P-030):** `tools/bench_action.sh` sends a debug command that
  also toggles a black square in the corner; that frame stands in for the tap ripple. End = last frame with
  mean-abs-diff ≥ 2. Blank frames during chapter-boundary turns: `tools/blank_frames.py` (ink fraction of every
  frame in the page area).
- **Search (P-040..P-044):** `tools/bench_search.sh`, logged in-app timestamps from the search command to the
  first results added to the visible list and to the final count.
- **Memory (P-060..P-062):** `tools/mem_total.sh`, TOTAL PSS of the app process plus its WebView renderer.
- **Rendering and robustness:** `tools/run_checks.sh` screenshots and event logs.

Tooling notes: the spec's `tools/summarize_open.sh` mis-parses frames whose mean-abs-diff is ≥ 100 (awk reads
field `$3`, but `frame_changes.py` prints `mad=112.70` without the space), dropping the paint frame of a full-screen
change; `tools/summarize_open_fixed.sh` applies the same rule with pattern parsing. agent-device recordings failed
intermittently ("Android screenrecord ownership could not be confirmed for pid …"); affected runs were repeated.

## 4. Results: opening books (P-010..P-016)

Two shell modes were measured with the same engines. **Activity** mode is the spec's method applied to a
library activity → reader activity hop. **In-place** keeps the book list in the reader activity with the engine
prepared behind it (WebView created; for foliate-js also the host page and JS modules loaded). In activity mode
≈0.9–1.1 s of every open is the activity start + window transition (split measured from the recordings), i.e.
shell cost, not engine cost. The production app should open books in-place (or pre-create the reader), so the
in-place column is the one that compares engines. Video = frame-diff on recordings (ms); medians of 3.

| Id | Book | Must / Stretch | Readium activity | foliate activity | **Readium in-place** | **foliate in-place** |
|---|---|---|---|---|---|---|
| P-010 | Alice | ≤1500 / ≤700 | 2433 | 2725 | **1053** ✅ | **431** ✅✅ |
| P-011 | War and Peace | ≤1500 / ≤800 | 3224 | 2711 | **1776** ❌ | **440** ✅✅ |
| P-012 | 2000 chapters | ≤2000 / ≤1000 | 2651 | 2340 | **2818** ❌ | **337** ✅✅ |
| P-013 | 200 images (127 MB) | ≤2000 / ≤1000 | 2760 | 2401 | **1581** ✅ | **463** ✅✅ |
| P-014 | huge 10 MB chapter | ≤3000 / ≤1500 | > 45 s (still spinner at 45 s) | 20054 | **84406** ❌ | **10628** ❌ |
| P-014 | huge chapter, foliate + chunking layer (§7) | ≤3000 / ≤1500 | — | — | — | **863** ✅✅ |
| P-016 | reopen (same process) | ≤50% of first / ≤400 | Alice 1686, W&P 1918 | Alice 2327, W&P 2319 | Alice 864, W&P 1119, 2000ch 1307, 200img 1367, huge 84940 | Alice 347, W&P 342, 2000ch 265, 200img 359, huge 9884; chunked huge 1032 |

✅ = Must met, ✅✅ = Stretch met. Without the WebView pre-warm, activity-mode Alice was 2919 (Readium) and
2924 ms (foliate), i.e. pre-warming Chromium saves ≈0.3–0.5 s for both.

In-place, foliate-js meets every Stretch target for normal books, and its reopen (P-016) meets the ≤400 ms
stretch. Readium meets the Must target for P-010 and P-013 only; its open cost grows with spine size (2000
chapters: 2.8 s; the navigator fragment computes positions for all resources with `runBlocking` when it is created
and sets up a ViewPager page per resource — likely contributors, not profiled). P-015 (reader frame ≤100 ms) is a shell matter: in
place the frame is shown immediately for both.

## 5. Results: reading actions (War and Peace unless noted)

Two numbers per cell: **video** (frame-diff, command marker → last large change) and **log** (command → engine's
"page shown" callback + 2 animation frames). For reflow and rotation they disagree: the final relayout after the
large change is sometimes below the frame-diff threshold (verified on frames for foliate rotation: the rotated
frame first shows the old line breaks), so the truth lies between the two; the log value is the conservative one.
Readium sends no page callback after a text-size change, so it has no log value there.

| Id | Scenario | Must / Stretch | Readium video / log | foliate video / log |
|---|---|---|---|---|
| P-020 | text size step → reflowed page | ≤100 / ≤60 | **81** / — ✅ | **126** / 258 ❌ (close) |
| P-021 | book-wide page total correct after layout change | ≤5 s / ≤2 s | ❌ not provided (see §8) | ❌ not provided (see §8) |
| P-022 | rotation, position kept | ≤900 / ≤500 | 442 / **1078** ⚠️ | 356 / **866** ✅ (borderline) |
| P-023 | rotation, huge chapter | ≤3000 / ≤1500 | ❌ (open alone takes 84 s) | ❌ unchunked (re-layout of the whole 10 MB document); chunked: see §7 |
| P-026 | chapter-boundary page turn | ≤400, no blank / ≤250 | **240** / 560, **no blank frame** ✅ | **633** / 671, **blank frames ≈230 ms** ❌ |
| P-030 | TOC jump to Second Epilogue | ≤700 / ≤400 | 285 / 758 ✅ | 76 / 194 ✅✅ |

Notes:
- Readium's ViewPager keeps the neighbouring resources loaded in their own WebViews, which is why its chapter
  turn has no blank frame. foliate-js loads one section at a time in one iframe: the next section is fetched,
  parsed and laid out only when the user turns past the end, and the slide animation shows white meanwhile.
- Position kept after a rotation round trip: see §6b.

## 6. Results: search and memory

Search times are in-app logged (search command → first results added to the visible list / final count shown),
medians of 3 (single runs where marked ¹). Both engines are case- and diacritic-insensitive ("Borodino" finds
all 108 "Borodinó" hits) and found the single `MARKERWORDMANY` hit in chapter 1777.

| Id | Query | Must / Stretch | Readium first / done | foliate first / done |
|---|---|---|---|---|
| P-040 | "the", War and Peace | first ≤500; full ≤3 s or progressive | **269** / 53 213 (47 358 hits, no cap) ✅ progressive | **297** / 4 271 (capped at ≈5000) ✅ |
| P-041 | "Borodinó" (108 hits) | ≤1500 / ≤700 | 2 714 / **5 532** ❌ | 7 468 / **12 572** ❌ |
| P-042 | "zygote" (0 hits) | ≤1200 / ≤600 | **5 470** ❌ | **12 374** ❌ |
| P-043 | "augmented matrix", Linear Algebra | first ≤3000 / ≤1500 | **398** ✅✅ (done 3 699) | **598** ✅✅ (done 12 370) |
| P-044 | "MARKERWORDHUGE", huge chapter (138 hits¹) | ≤2000 | **2 091** ❌ (borderline) | **18 008** ❌; chunked 11 171 / 15 444 ❌ |
| F-050 | "MARKERWORDMANY", 2000 chapters¹ | — | 52 125 / 65 420 | 13 941 / 14 889 |

Memory, TOTAL PSS of app + WebView renderer (MB):

| Id | Scenario | Must / Stretch | Readium | foliate | foliate + chunking |
|---|---|---|---|---|---|
| P-060 | War and Peace after 20 page turns | ≤215 / ≤150 | **236** ❌ (118 + 124) | **212** ✅ (95 + 122) | 189 ✅ |
| P-061 | huge chapter, steady / peak sampled while loading | ≤300 / ≤400 | **924 / 1 280** ❌ | **881 / 1 352** ❌ | **355 / 354** ⚠️ (peak ✅, steady 55 over) |
| P-062 | 200-image book after 10 / 50 plates | ≤300, growth ≤10% | **212 → 214** ✅ | **232 → 230** ✅ | 232 → 230 ✅ |

The app process was alive in every memory sample. During the unchunked foliate huge-chapter load one renderer
process vanished between listing and sampling (`/proc/<pid>` gone) — possibly a renderer restart under memory
pressure; not investigated further.

Observations:
- Neither engine's search meets P-041/P-042/P-044. Readium scans resources sequentially with ICU on a
  background thread; foliate parses every section into a DOM in the WebView and runs an `Intl.Collator` matcher
  on the JS thread. Search should be **our own Kotlin component** over plain text extracted once per book
  (diacritic-folded, cached on disk, streamed results, capped), returning text positions that the engine resolves
  to a location only when a result is opened. That is engine-independent work either way.
- The chunked huge chapter's app-process share (110 MB) includes all 80 parts held in RAM by the prototype;
  serving parts from an on-disk cache should bring steady state under 300 MB (not measured).

## 6b. Rendering (F-080..F-083) and robustness (R-001..R-004, R-007)

Screenshots via `tools/run_checks.sh` (both engines, same commands).

| Check | Readium | foliate-js |
|---|---|---|
| F-081 RTL Arabic (`regime-anticancer-arabic`) | ✅ right-aligned, rtl progression | ✅ right-aligned, `dir: rtl` detected |
| F-081 vertical Japanese with ruby (`kusamakura`) | ✅ | ✅ |
| F-082 fixed layout manga (`haruko-html-jpeg`) | ✅ whole page fitted | ✅ whole page fitted |
| F-082 `page-blanche` | ⚠️ blank page 2 (book is "white page"; inconclusive) | ⚠️ same |
| F-083 MathML (`linear-algebra` matrices) | ✅ | ✅ |
| F-083 obfuscated font (`wasteland-otf-obf` vs `-woff`) | ✅ same face as the WOFF edition | ✅ same face |
| F-083 SVG in spine (`svg-in-spine`, `sous-le-vent`) | ⚠️ blank page after ≈7 s (not investigated) | ✅ both render |
| F-080 EPUB 2 NCX TOC (`epub2-ncx-only`) | ✅ | ✅ |
| R-007 book scripts disabled (own `script-test.epub`) | ❌ "SCRIPT RAN" — needs a CSP injected into served resources | ✅ blocked by host CSP |
| F-037 same passage after rotate land → port | ❌ back to page 1/14 of the chapter (was 4/14); positions are coarse | ✅ same passage |
| R-001 `malformed-missing-files-bad-xml` opens | ✅ opens; good chapters fine | ✅ opens; good chapters fine |
| R-001 bad-XML chapter text visible | ❌ Chromium XML error box, only the heading before the first error | ✅ full text (lenient HTML fallback) |
| R-001 missing chapter / TOC entry to missing file | ❌ Chromium "webpage not available" page | ⚠️ no crash, lands on a neighbouring chapter, no notice |
| R-002 broken OPF, not-a-zip, truncated, empty spine | ✅ error screen, no crash | ✅ error screen, no crash (empty spine reported via a JS exception text) |
| R-003 no container | ✅ reported | ✅ reported ("Failed to load container file") |
| R-004 `fake.epub` | ✅ reported | ✅ reported |

Placeholders for missing items (F-084) and friendly error texts are host work for either engine; with foliate the
host owns the loader, so a placeholder document can be served for a missing spine item in a few lines.

## 7. Third approach: a Kotlin book-transform layer in front of the engine (huge documents)

Why it was added: the only open target both engines fail badly is P-014 (and with it P-023, P-044, P-061),
and the cause is the same in both: one spine document is laid out as a whole (10 MB → 10.6 s in foliate-js,
84 s in Readium; 0.9 GB PSS). This is the "cost scales with the size of one content document" problem the spec
singles out. Rather than a third renderer, the prototype adds a small pre-processing layer that the engine never
sees: `prototypes/foliate/.../Chunker.kt`.

- Spine documents > 512 KB are split, in one forward scan of the markup (no DOM), at element boundaries at body
  level (or one level below a single wrapper element, whose start tag is repeated) into parts of ≈128 KB; each
  part keeps the original `<head>` so the book's CSS still applies.
- The OPF spine/manifest, the nav document and the NCX are rewritten so TOC anchors point into the right part;
  in-document `#id` links are retargeted across parts. Parts are served as virtual zip entries.
- Result on the huge chapter (foliate, in-place, 3 runs): **open 863 ms** (Stretch ≤1500 ✅✅; was 10 628),
  reopen 1 032 ms; split cost 726 ms of that (80 parts) — cacheable per book; TOC jump to "Section 40" lands on
  the right heading; memory 355 MB steady / 354 MB peak (was 881 / 1 352).
- Not done in the prototype (production work): map reading positions/CFIs between the original document and
  parts (so positions and highlights survive a change of the split policy), CSS that depends on sibling position
  across a cut (`:first-child`, `+`, counters), links into a split document from other content documents,
  on-disk cache of parts, and lenient-HTML input (the scanner assumes reasonably balanced tags; it falls back to
  no split if it finds no cut points).
- The layer is engine-agnostic in principle, but only cheap to apply to foliate-js, where the host owns the
  loader: Readium would need a custom `Container`/manifest transform plus mapping of its `Locator`s and
  positions, inside a navigator that is not designed for it.

## 8. Effort and risk for the failing targets

| Target | Readium | foliate-js |
|---|---|---|
| P-010..P-013 open | **High.** Open cost is inside the navigator (positions computed at fragment creation, a ViewPager page + WebView per resource, hidden until JS reports ready). Meeting P-011/P-012 needs changes in Readium's navigator, i.e. a fork. | **Met** in place. Keep the host page loaded while the library is shown. |
| P-014 / P-023 / P-061 huge document | **High.** Chunking needs a container/manifest transform plus `Locator`/positions mapping; open is 84 s today. | **Medium.** Chunking layer prototyped (§7): open 0.86 s. Remaining: CFI mapping, disk cache, CSS edge cases. P-023 with chunks re-lays out only one ≈128 KB part (not measured). |
| P-020 reflow | Met. | **Low.** 126 ms vs 100 ms Must; foliate re-renders the section and re-anchors; likely within reach by applying the size change as a CSS variable (no style-sheet rewrite) and skipping work for off-screen overlays. Needs a real-device check. |
| P-021 book-wide page total | **High** for both: neither engine computes real page totals. Readium "positions" are 1024-byte slices of the compressed resource; foliate "locations" are 1500-character slices. Both give exact per-chapter page counts immediately. Getting an exact book-wide total within 5 s after every layout change means laying out every section at the new settings: a background pass in a hidden WebView would cost roughly (sections × per-section layout) — on this emulator foliate lays out a War and Peace chapter in tens of ms, so 368 chapters ≈ 10–20 s. Plan: show "≈ N" immediately (characters ÷ characters-per-page measured on the current and recently laid-out sections), refine in the background, cache per (book, settings). The ≤5 s exact bound is the riskiest target in the spec for any WebView engine. | same |
| P-022 rotation | ≈1.1 s by log (fails by log, passes by video). | ≈0.87 s by log (borderline pass). Same work as P-020. |
| P-026 chapter boundary | Met (neighbour pages preloaded by the ViewPager). | **Medium.** foliate's paginator holds one section; needs a second, pre-loaded iframe for the next/previous section and a swap at the boundary (changes in `paginator.js`, which we would fork; MIT). The sections are already small and load in ≈100–200 ms, so pre-loading removes the wait entirely. |
| P-040..P-044 search | **Medium**, engine-independent: own Kotlin search over cached plain text (§6). | same |
| R-007 scripts off | Book scripts run unless we inject a CSP into served resources (Readium serves resources through its own `WebViewServer`; doable, small). | Done by the host page CSP. |

## 9. Recommendation

**Build on foliate-js (MIT) in a WebView, behind a thin Kotlin host that owns the book container, plus three
pieces of our own: the chunking layer (§7), neighbour-section preloading in the paginator, and a native search.**

Evidence:
- Opening: with the reader pre-created, foliate-js opens Alice/War and Peace/2000 chapters/200 images in
  0.34–0.46 s (all Stretch targets) and reopens in ≈0.3 s; Readium needs 1.05–2.8 s and misses P-011/P-012,
  and its open path is inside the navigator.
- Huge documents: both fail as-is (10.6 s vs 84 s; ≈0.9 GB). A Kotlin chunking layer, easy to slot in front of
  foliate-js because the host serves every byte, brings the 10 MB chapter to 0.86 s and 355 MB.
- Robustness and rendering: foliate-js passes the damaged-book check (bad XML shown leniently), disables book
  scripts, keeps position on rotation, and renders SVG spine documents; Readium failed those four as configured.
- Where Readium is better: chapter-boundary turns (no blank frame, 240 ms) thanks to preloaded neighbours, text-size
  reflow (81 ms vs 126 ms), and a mature, typed Kotlin API (locators, decorations, TTS, LCP). The first two are
  bounded engineering tasks on foliate's paginator; LCP/DRM is not a requirement in the spec.
- Licences: foliate-js MIT, nothing else bundled; Readium BSD-3 with permissive deps. Both fine.

Risks of the recommendation:
1. foliate-js has no releases; we would vendor a pinned commit and own a fork of `paginator.js` (preloading,
   reflow speed). It is small (≈1.2 k lines) and readable.
2. All engine state lives in JS; the host must keep a clean bridge (positions as CFI strings, events) and keep
   the reader WebView alive across screens to keep opens fast.
3. P-021 (exact book-wide page total within 5 s) is not met by any engine and is the riskiest target; see §8.
4. P-026 currently fails (blank flash ≈230 ms) until preloading is implemented.
5. Search must be written by us in either case (§6).

## 10. Not measured / limitations

- P-001..P-003 (app start), P-015 as a spec measurement, P-025/P-027 jank (gfxinfo), P-031 (2000-entry TOC panel
  smoothness), P-050..P-052 and P-063 (library), R-008..R-010: not engine questions or need the real app.
- P-023 (rotation on the huge chapter) not measured: Readium cannot open the book in reasonable time; foliate
  unchunked would re-lay out 10 MB; chunked was not timed.
- Frame times on this emulator are relative only (spec note); no real-device runs.
- Rotation and reflow: frame-diff and in-app timing disagree by up to ≈0.6 s (§5); both reported.
- Open timings use 3 runs; a few recordings failed (agent-device "screenrecord ownership could not be confirmed")
  and are reported from the remaining runs (n=2 where shown).
- Gutenberg files downloaded 2026-10-30.
