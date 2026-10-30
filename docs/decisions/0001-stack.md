# ADR 0001: Rendering engine and application stack for the MVP

- Status: Accepted (decided by the user, 2026-10-30)
- Inputs: `docs/engine-evaluation.md` (engine bake-off), spec v0.2/v0.3 (`~/Projects/epub-reader-spec`:
  features.md, performance.md, corpus.md, HANDOFF_LOG.md), `CLEANROOM.md`
- Scope: the first usable release (MVP), Android first

## Context

We are building a fast, offline-first, MIT-licensed EPUB reader in a clean room. The spec sets release-blocking
performance targets ("Must" column of performance.md) and robustness requirements (R-001..R-010), and asks the
architecture to leave room for our own sync backend later (F-115) without building it now (F-110).

The engine bake-off compared Readium Kotlin Toolkit 3.4.0 and foliate-js (commit `78914ae`) in identical shells
on the reference emulator. Key evidence (engine-evaluation.md §4–§9):

- foliate-js with the reader pre-created opens Alice / War and Peace / 2000 chapters / 200 images in 0.34–0.46 s
  (all Stretch targets) and reopens in ≈0.3 s; Readium needed 1.05–2.8 s and misses P-011/P-012, with the cost
  inside its navigator.
- Both engines fail the 10 MB single-chapter book (10.6 s / 84 s, ≈0.9 GB). A Kotlin chunking layer in front of
  foliate-js brought it to 0.86 s and 355 MB; it is cheap only where the host serves every byte, i.e. foliate-js.
- foliate-js passed the damaged-book check (lenient HTML fallback), blocks book scripts through the host page
  CSP, keeps the passage on rotation and renders SVG spine documents; Readium failed those as configured.
- Neither engine's search meets P-041/P-042/P-044, and neither computes a book-wide page total (P-021).
- foliate-js loses on chapter-boundary turns (≈230 ms blank, P-026) because it holds one section at a time.

## Decision

### Engine

**foliate-js (MIT) in the platform WebView**, vendored at a pinned commit, with three additions of our own:

1. **Kotlin chunking layer.** Content documents above a size threshold are split at element boundaries before
   the engine sees them. The layer owns a bidirectional **position mapping** between the original document and
   its parts (CFIs and plain-text offsets), so stored positions, bookmarks and highlights always refer to the
   *original* document and survive a change of the split policy. Parts are cached on disk, not in RAM.
   Targets: P-014, P-023, P-044, P-061.
2. **Neighbour-chapter preloading** in a fork of foliate's paginator (previous/next section laid out in their own
   frames and swapped in at the boundary) to remove the boundary blank. Target: P-026 (≤ 400 ms, no blank frame);
   also the basis for seamless scroll across chapters (F-031).
3. **Our own in-book search** over plain text extracted once per book, stored in **SQLite FTS5** (unicode61
   tokenizer, diacritics removed), streaming capped results; hits are resolved to an engine location only when
   opened. Targets: P-040..P-044, F-050.

**P-021** (exact book-wide page total ≤ 5 s after a layout change, "≈ N" within ±5 % until then) is not met by
any engine and is the riskiest target in the spec. It gets a time-boxed spike in the first milestone after the
skeleton, before production reader work depends on it.

### Application stack

- **Kotlin Multiplatform.** Android is the only shipping target for now. Code is arranged so iOS/desktop can be
  added later: everything that is not platform glue lives in `commonMain` of shared modules; platform services
  sit behind interfaces or `expect` declarations.
- **Shared core** (KMP): EPUB parsing (own zip reader, container/OPF/nav/NCX), metadata and cover location,
  book identity, lenient markup scanning, the chunker and its position mapping, plain-text extraction, the
  **Room KMP** database designed for sync per F-115 (stable string IDs, change timestamps, tombstones), the
  **FTS5** search index (bundled SQLite driver), and **coroutines** for all background work.
- **UI: Compose Multiplatform** with **Material 3** components and **our own visual design** (own tokens,
  typography and colour; nothing copied from another reader, per CLEANROOM.md).
- **Reader:** foliate-js wrapped in a small **TypeScript** layer built with **Vite**, bundled as app assets,
  talking to Kotlin through a **typed message bridge** (JSON messages, both sides tested against shared fixtures).
  Book content is served **from the zip through a WebView asset-loader path handler** (as in the prototype),
  never copied wholesale into JS memory. **Book scripts are blocked** by the host page CSP (R-007).
- **Android-specific:** storage scanning and file watching, open-with intents, **WorkManager** for deferred
  background work, **Coil** for covers (from our own thumbnail cache).
- **Distribution flavors:** `play` and `fdroid`. Billing code is isolated in the `play` flavor; the `fdroid`
  flavor contains no proprietary libraries. The MVP ships **no billing**, only the flavor seam and a build check
  that keeps proprietary artifacts out of `fdroid`.
- **minSdk 26.** compile/target SDK 37 (as in the bake-off toolchain).
- **No backend in the MVP.** F-110 offline-first applies: no account, no network needed for reading, no analytics
  without opt-in (F-092). F-115 is honoured in the data model only.

### Quality gates

- The **performance.md "Must" targets are release gates.** An automated benchmark harness runs them on the
  reference emulator (AVD `Medium_Phone`, driven through the T3 device tools on host `local`) with the spec's
  measurement methods, and fails on a missed Must target or on regression against the stored baseline.
- **Unit tests** for the core (host JVM), **robustness tests** over the malformed corpus books (R-001..R-010),
  and instrumented tests on the emulator.

## Reasoning

- **Why foliate-js over Readium:** it met every open Stretch target for normal books in place, passes the
  robustness and rendering checks that Readium failed, and — decisively — lets the host own the loader, which is
  what makes the chunking layer, placeholder documents (F-084) and script blocking cheap. Readium's advantages
  (boundary turns, reflow speed, typed API) are bounded engineering tasks on foliate's ≈1.1 k-line paginator,
  whereas Readium's open-time and huge-document problems sit inside its navigator and would need a fork.
- **Why our own chunker, preloader and search:** these are exactly the targets neither engine meets
  (engine-evaluation.md §8). They are engine-independent in principle but cheapest with foliate-js.
- **Why FTS5:** the bake-off showed per-search DOM parsing is too slow (7–18 s). An index built once per book
  makes rare-word and no-hit queries (P-041/P-042) cost milliseconds, and unicode61 with diacritic removal gives
  case- and diacritic-insensitive matching (F-050) for free. Android's framework SQLite does not reliably ship
  FTS5, so we use the bundled SQLite driver.
- **Why KMP + Compose Multiplatform:** the user wants iOS/desktop later. Parsing, chunking, search, data and
  most UI are platform-neutral; keeping them in common code now avoids a rewrite. The reader is a WebView on
  every platform foliate-js can run in.
- **Why Room KMP with sync-ready rows now:** F-115 requires that sync can be added later without lossy
  migration. Stable IDs, `updatedAt` timestamps and `deleted` tombstones cost little today and are hard to add
  retroactively to users' data.
- **Why a typed bridge:** all engine state lives in JS (evaluation risk 2). A typed, fixture-tested protocol is
  the cheapest defence against silent Kotlin/TS drift.
- **Why flavors now:** F-112 requires that a build without proprietary billing can be produced; the seam is
  cheapest before any billing code exists.

## Consequences

- We own a fork of foliate-js's `paginator.js` (and small patches elsewhere). The vendored copy records the
  upstream commit and every local patch (`reader-web/vendor/foliate-js/PATCHES.md`).
- All engine state is in JS; the reader WebView must be kept alive behind the library to keep opens fast
  (allowed by HANDOFF_LOG v0.3, answer 2).
- Positions, bookmarks and highlights are stored against original documents; the chunk mapping is on the
  critical path for data integrity and gets the most thorough tests.
- P-021 may need a gatekeeper decision if the spike cannot reach ≤ 5 s for the exact total; the "≈ N" estimate
  path is built regardless.
- Broad storage discovery (F-010) needs "All files access" on Android 11+; this is acceptable for `fdroid`
  but is a store-policy risk for `play` (recorded as an open question in the MVP plan).
- Third-party licences to ship in "about/licences": foliate-js (MIT), bundled fonts (SIL OFL 1.1), AndroidX/
  Kotlin/Compose/Coil/kotlinx (Apache-2.0) and anything else added, recorded in
  `docs/licenses.md`.
