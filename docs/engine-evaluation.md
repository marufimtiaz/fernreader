# EPUB rendering engine evaluation

Status: draft — numbers being filled in.

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
