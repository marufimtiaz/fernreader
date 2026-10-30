# Spike S1 — P-021 page totals (War and Peace)

Date: 2026-10-30. Device: AVD `Medium_Phone` (adb serial `emulator-5554`), spike app `spike.foliate`
(`spikes/foliate-spike`, foliate-js `78914ae` + spike patches). Book: `war-and-peace-epub3.epub` (368 spine items,
all linear). Viewport: paginator container 386.75 × 866.3 CSS px, gap 24.69 px, column 362.06 px (logged by `count_start`).
Raw data: `raw-s1-times.txt`, `raw-s1-agreement.txt`, `raw-s1-estimate.txt`, `raw-s1-turns.txt`,
`raw-s1-textlen.json`, `raw-s1-counts-<font>.json`. Videos under `results/raw/s1/` (not committed).

**Decision: (A) — go.** The batched hidden-layout counter produces the exact total in ≤ 5 s (medians
2.9–4.2 s) with 389/389 sections equal to the visible paginator, and a stratified first batch gives an "≈ N"
estimate within ±2.3 % after ≈ 0.2–0.4 s. Stretch (≤ 2 s) is **not** met.

## Counter design (what was measured)

`spikes/foliate-spike/app/src/main/assets/host/count.js`.

- **sequential / pipelined:** each section is laid out in foliate's own `View` class (exported by a one-line spike
  patch) inside an invisible host div of the paginator container's size, with the paginator's last layout object
  (`renderer.layout`: width, height, gap, columnWidth, margin) and the same injected style sheets
  (`renderer.styles`) and `lang`/`dir`. Count = `view width / page width − 2`, i.e. exactly what
  `Paginator.pages − 2` reports. `pipelined` = 2 (or 4) such views in parallel.
- **batched:** one hidden iframe per batch; every section's resource-replaced document (the same blob that
  `section.load()` gives the visible view) is parsed, and its `<body>` children are placed in a
  `div.er-box` that carries the paginator's column CSS (`column-width: trunc(columnWidth)`, `column-gap: gap`,
  `height`, `padding: 0 gap/2`, `overflow-wrap: break-word`, image max-height `height − 2·margin`); book style
  sheets of all sections in the batch + our two style elements go in the head. Count per box =
  `ceil((contentRange.right − box.left) / pageWidth)` (same formula as `View.expand`).
- **stratified / mixed:** batched, but the first batch is a sample spread evenly over the book (every
  n/k-th linear section); the estimate is computed after that batch, then the rest is counted in reading order.
  `mixed` = first batch 12 sections, then batches of N.

Ruling (deviates from the plan's `count.js` sketch): the plan's `columnize()` used `padding: 0 margin` and
`column-width: width − 2·margin`; foliate actually uses `padding: 0 gap/2` with `gap = g/(1−g)·width` and
`column-width = width − gap` (margin is vertical only). The spike reuses foliate's own `View` for the
sequential/pipelined strategies and mirrors the real CSS in the batched one instead of fudging.

## Time to exact total (medians of 3, ms; total pages identical in every run)

| Strategy | font 1.0 (2341 p) | font 1.3 (3770 p) | font 1.8 (7111 p) |
|---|---|---|---|
| sequential (1 View) | 19 566 | 20 879 | 20 191 |
| pipelined, 2 Views | 13 035 | 12 347 | 14 859 |
| pipelined, 4 Views | 8 121 (1 run; **9 sections +1 page, total 2350**) | — | — |
| batched, 24 / batch | 3 576 | 4 204 | 3 880 |
| batched, 48 / batch | 2 904 | — | 3 769 |
| batched, 96 / batch | 2 850 | — | 3 945 |
| mixed 12 + 96 (from `raw-s1-estimate.txt`) | 2 830 (3 runs: 2830, 3792, 2725) | 3 161 (2 runs) | 3 679 |
| mixed 12 + 24 | 2 904 | — | 3 498 |

Per-run raw numbers: `raw-s1-times.txt` (sequential/pipelined/batched), `raw-s1-estimate.txt` (stratified/mixed).
Font 1.3 batched/24 had one run of 5003 ms in the agreement session (`raw-s1-agreement.txt`), so with 24-section
batches we sit at 70–100 % of the 5 s budget on this emulator.

Notes: the 4-lane pipelined run disagreed with all other strategies in 9 sections. Likely cause: lanes share
foliate's ref-counted resource loader; one lane's `unload()` can revoke a shared stylesheet/image blob while another
lane's document still loads it. Not investigated further (pipelined is far from 5 s anyway).

## Agreement with the visible paginator (C-003)

After a batched/24 count, `sample` showed each listed section in the visible paginator and logged
`pages − 2` next to the counted value.

| Font | Sections checked | Equal |
|---|---|---|
| 1.0 | 21 (indices 5, 23, …, 365) | 21/21 |
| 1.3 | 21 (same) | 21/21 |
| 1.8 | **all 368** | 368/368 |

All 40 counting runs (every strategy except 4-lane) produced identical per-section arrays at the same font.
Limitation: W&P has no embedded fonts, one style sheet and two images (cover). Books with per-section style
sheets, `body`-level classes, `:first-child`-style selectors on `body > *`, or vertical/RTL writing were not tested
here; the batched box changes the document structure for exactly those cases (see "Risks for M5").

## Estimate ("≈ N") error

Offline (`spikes/tools/s1_estimate.py`, exact per-section counts at 3 fonts):

| Estimator | Seeds | Worst error over all seeds/transitions | Within ±5 % |
|---|---|---|---|
| plan's text model (`cpp = Σchars/Σpages` of current ± 1) | 3 visible sections | −1.2 % … +84.7 % | 99/366 positions |
| text model, last page counted half-full, skip < 200 chars | 3 visible sections | −7.7 % … +69.5 % | 310/366 |
| scale previous layout's exact counts by seed ratio | 3 visible sections | −31 % … +46 % | 301–352/366 |
| **scale, 12–48 stratified sections** | stratified first batch | −3.1 % … +3.2 % | 42/42 |
| **text model (half-page), 12–48 stratified sections** | stratified first batch | −2.7 % … +2.5 % | 42/42 |

Front matter (cover, title, contents list) has very low text density, so any estimate seeded only from the
neighbourhood of the reading position is unreliable. Seeding from a stratified sample fixes it.

On device (`raw-s1-estimate.txt`, time from count start to estimate; medians of 3):

| Config | Transition | Estimate ready (ms) | Estimate error | Exact (ms) |
|---|---|---|---|---|
| stratified 24 (scale) | 1.0→1.3 / 1.3→1.8 / 1.8→1.0 / 1.0→1.1 | 426 / 327 / 422 / 448 | −1.0 / −0.1 / +1.0 / −0.6 % | 3807 / 3128 / 2880 / 3875 |
| stratified 24 (text, no previous counts) | →1.0 / →1.3 / →1.8 | 611 / 655 / 637 | +1.0 / −0.2 / −0.3 % | 4045 / 4146 / 3955 |
| stratified 12 throughout (scale) | 1.0→1.3 / 1.3→1.8 / 1.8→1.0 / 1.0→1.1 | 308 / 206 / 212 / 339 | +0.6 / +0.5 / −1.1 / +2.3 % | 5117 / 4670 / 4262 / 4994 ❌ |
| stratified 12 throughout (text) | →1.0 / →1.3 / →1.8 | 370 / 262 / 364 | −0.9 / −0.8 / −0.2 % | 5631 / 5853 / 5291 ❌ |
| **mixed 12 + 24 (scale)** | 1.3→1.0 / 1.3→1.8 | 250 / 213 | −0.6 / +0.5 % | 2904 / 3498 |
| mixed 12 + 96 (scale) | 1.0→1.3 / 1.3→1.8 / 1.8→1.0 | 328 (2 runs) / 229 / 383 | +0.6 / +0.5 / −1.1 % | 3161 / 3679 / 2830 |
| mixed 12 + 96 (text) | →1.3 | 363 | −0.8 % | 3226 |

The visible page reflows independently of the counter (P-020 path); the "≈" value can be shown once the first
batch is done (≈ 0.2–0.4 s) — before that the previous exact total is stale, so the UI should show "≈" with the
previous total scaled by the visible section's page ratio, or no total, for that fraction of a second.

## Page turns while counting (font 1.8, in-chapter `next`, `tools/bench_action.sh`, video ms)

| Condition | Runs | Median |
|---|---|---|
| idle | 414, 304, 251 | 304 |
| during sequential count | 383, 358, 427 | 383 |
| during mixed 12 + 96 | 750, 450, 944 | **750** |
| during mixed 12 + 48 | 648, 567, 418 | 567 |
| during mixed 12 + 24 | 456, 427, 462 | 456 |

A batch is laid out in one synchronous layout pass on the renderer's main thread, so a turn requested meanwhile
waits for the batch: ≈ 10 ms per section. 24-section batches keep the delay at ≈ +150 ms and the exact total under
5 s. Blank-frame analysis of these turns was not run (in-chapter turns, no section load involved).

## Decision and design for M5 Task 5.3

(A) adopted:

1. Counter = **batched** hidden layout in the reader WebView (`count.js` design above, rewritten in TS), started on
   every layout change (font, spacing, margins, viewport/rotation, publisher-styles toggle), cancelled and restarted
   if another change arrives. First batch **12 stratified sections**, then **24-section batches** in reading
   order, each batch after a `setTimeout(0)` yield (and deferred while a page-turn animation runs).
2. Estimate after the first batch: if exact counts for the previous layout of the same book exist, scale them by
   the sample ratio; otherwise text model with `C = Σchars / Σ(pages − 0.5)` over the sample sections with
   ≥ 200 chars (section char counts from Kotlin's `TextExtractor`, cached per book). Shown as "≈ N".
3. Exact per-section arrays cached per (bookId, layoutKey) so returning to a previous layout is instant, and the
   visible paginator's own page count for the current section always overrides the counted one (C-003: any
   difference is logged as a bug event).
4. Bench P-021 measures `layout_change → page_total_exact` and logs the estimate error.

## Risks for M5

- Batched layout changes document structure: `body` attributes/classes and `body > x` selectors, per-section
  `<style>` that leaks into other boxes in the same batch, `@page`/`html`-level rules, vertical or RTL writing
  modes. Mitigation to build in M5: group sections into batches only when their head style-sheet sets are equal and
  `body` has no attributes; otherwise count that section alone in a foliate `View` lane (≈ 55 ms/section measured).
  Then run the agreement check over the whole corpus, not just W&P (bench task).
- 70–100 % of the 5 s budget is used on this emulator; a 2× bigger book would miss it. The per-layout cache and the
  estimate keep the UX acceptable, but the Must is book-size dependent (question below).
- Books with web fonts: the counter awaits `document.fonts.ready` and image load events per batch; untested here.

## Questions for the gatekeeper

- **Q-S1-1 (P-021 scope).** P-021 names War and Peace (≈ 2 200 pages, 3.6 MB text); we measure 2.9–4.2 s there. For
  larger books (Shakespeare complete, Linear Algebra) the exact total scales with text size and will exceed 5 s. Is
  P-021's 5 s limit meant only for W&P-sized books, with "≈ N" (within ±5 %) acceptable for longer for bigger books?
  *Proposed:* yes — 5 s gate on W&P as written; for other books "≈ N" until exact, no time limit, estimate ±5 %.
- **Q-S1-2 (first ~0.3 s).** Between the layout change and the first estimate (0.2–0.4 s) no up-to-date total
  exists. *Proposed:* hide the total (show "page N" only) or show the previous total prefixed with "≈" during that
  window; both satisfy "show ≈ N until exact" in spirit.
