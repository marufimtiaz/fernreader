# Spike S2 — neighbour-section preloading (P-026, F-031)

Date: 2026-10-30/31. Device: AVD `Medium_Phone` (adb serial `emulator-5554`), spike app `spike.foliate`.
Book: War and Peace, font 1.0, starting at "BOOK ONE: 1805"; each measured turn is a `next` from the last page
of a chapter (`spikes/tools/s2_boundary.sh`, which drives `tools/bench_action.sh` and `tools/blank_frames.py`).
Raw data: `raw-s2-base.txt`, `raw-s2-base2.txt` (unpatched), `raw-s2-pre.txt`, `raw-s2-pre2.txt`,
`raw-s2-pre3.txt` (patched), `raw-s2-mem.txt`. Videos under `results/raw/s2/` (not committed).

**Decision: go.** The patched paginator turns across a chapter boundary in a median of **125 ms**, with no blank
frame and the same CFIs as the unpatched paginator. Memory after 70 page turns is +4 MB.
Must (≤ 400 ms, no blank frame): **met.**

## Before / after (video ms, `bench_action.sh`; cmd marker → last large change)

| Paginator | Boundary turns measured | Median | Min–max | Blank frame (see rule below) |
|---|---|---|---|---|
| unpatched | 13 (of 15; 2 recordings failed) | **585** | 176–668 | 12 of 14 analysable videos |
| patched (`preload`) | 19 (of 23; 4 recordings failed) | **125** | 110–289 | 0 of 21 analysable videos |

Recording failures: `agent-device record` sometimes reported "screenrecord ownership could not be confirmed" or
"recording already in progress"; those turns have `video_ms=NA`, and their log timings (`log_ms`) are consistent
with the others. Two unpatched videos (`base2-6`, `base2-9`; the latter is the 176 ms outlier) show no blank
frame; not investigated (unpatched behaviour, not adopted).

**Blank-frame rule (ruling: deviates from the plan).** The plan's criterion "every `min_ink` > 0.01" cannot work:
the last page of a chapter can hold two lines (ink 0.0016–0.005), below 0.01 before the turn starts. Rule used
(`spikes/tools/s2_inkcheck.py`): a frame is blank/partial if its ink is below half of the smaller of the first
(source page) and last (target page) frame's ink. Unpatched turns show the slide fading to ink 0.0000 for
~3 frames; patched turns go from the source page's ink to the target page's in one step.

Note: the patched boundary turn is an instant swap (no slide animation); in-chapter turns keep the 300 ms slide.
A slide across the boundary is possible (both views are live elements) but was not built; P-026 and the
Must column do not require it (C-015: no extra work for non-Must polish).

## Memory (P-060, Must ≤ 215 MB)

70 `next` commands from "BOOK ONE: 1805" (≈ 0.7 s apart), then 3 × `tools/mem_total.sh`:

| Paginator | Boundaries crossed | Total PSS (MB), 3 samples |
|---|---|---|
| unpatched | 13 | 114, 117, 116 |
| patched | 11 | 117, 120, 120 |

At most three sections (current ± 1) are kept; nothing grows across boundaries. (The bake-off's 212 MB was a
different scenario, with a fresh process and the full library activity; this comparison is same-session, same
procedure.)

## Correctness

- **CFIs.** After each boundary turn the host logged `view.lastLocation.cfi`. For every chapter that both runs landed
  on (sections 3–6 and 8–12), the patched CFI equals the unpatched one, e.g.
  `epubcfi(/6/10!/4/2[pgepubid00005],/2,/6/1:776)` (`raw-s2-base2.txt` vs `raw-s2-pre2.txt`).
- **Search highlight — bug found and fixed.** On the first try, a search hit in the preloaded neighbour section
  opened at the right place but **without** the search highlight (the unpatched build drew it). Cause: view.js draws
  annotations and search results only into the documents returned by `renderer.getContents()`, which returned
  only the visible view; a preloaded section's overlayer is created before the search runs. Fix:
  `getContents()` also returns the loaded hidden views (current first). After the fix, the highlight is drawn in
  the swapped-in section (checked by screenshot). The same mechanism draws user highlights (F-061), so M3/M7 need a
  test for "highlight added while the section is preloaded shows after the swap".
- **Selection.** Long-press (`agent-device longpress`) on a word in a preloaded-then-shown section selects it and
  opens the system selection menu (screenshot).

## The patch (for M3 Task 3.4 to re-implement in `reader-web/vendor/foliate-js`)

All changes are in `paginator.js`, listed in the `// SPIKE PATCH (M0 S2)` header, enabled by the `preload`
attribute in paginated flow:

1. Every `View` lives in its own **slot** div inside `#container` (`position: relative`). The current slot is in
   flow (`height: 100%`); cached slots are `position: absolute; inset: 0; overflow: hidden; visibility: hidden`.
   An iframe must never be re-parented (that reloads it), so a swap only changes slot styles.
2. `#cache: Map<index, {slot, view, ready, loaded}>`. `#preloadIndex(i)` loads the section into a hidden view with
   the paginator's last layout (`#beforeRender` result kept in `#lastLayout`), injects the two style elements and
   applies the current styles, then dispatches `load` and `create-overlayer` as `#display` does.
3. `#turnPage(dir)`: on the first/last text page, if the neighbour is cached, `#swapTo()` it (await `ready` if still
   loading): hide the current slot, show the target slot, set `#view`/`#index`/background, and scroll to the first or
   last page **synchronously in the same task** (fraction anchors set `scrollLeft` directly), so no frame shows an
   intermediate state. Otherwise the original slide + load path runs.
4. `#goTo` swaps in a cached target (TOC/link/search into a neighbour); otherwise `#display` as before, but
   `#createView` keeps the old view in the cache and the old section is not unloaded in `onLoad`.
5. `#afterSwap()` (after `#display` and `#swapTo`): evict views farther than one linear section (destroy view,
   remove slot, `section.unload()`), preload missing neighbours.
6. `render()` re-renders every loaded cached view with the new layout; `setStyles()` applies to every cached
   document and re-expands it; `destroy()` evicts all.
7. `getContents()` returns loaded hidden views too (current first), so annotations and search hits reach them.

Known limits to handle in M3: preloaded views assume the current writing mode (vertical/RTL neighbours need
`#beforeRender` for their own direction before the swap); the onExpand of hidden views is ignored until they become
current; fixed-layout books (`foliate-fxl`) are not covered.

## Scrolled mode across sections (F-031) — design, no code

foliate's scrolled flow (`View.scrolled`, `#scrollNext/#scrollPrev`) holds one section; at the end of a section
`#scrollNext` returns `true` and `#turnPage` loads the next one, replacing the view. With the same cache:

- Stack the slots **vertically in reading order** inside `#container` (all in flow, `height` = the view's expanded
  height; no hidden slots in scrolled mode). `#container.scrollTop` spans prev + current + next.
- Current section = the slot containing the viewport's top edge (after each debounced scroll); `#index` follows it
  and `relocate` is emitted with that index. `fraction = (scrollTop − slot.offsetTop) / slot.height`; the visible
  range comes from `getVisibleRange(doc, start − slot.offsetTop, end − slot.offsetTop, mapper)` for the slot(s) in
  view, so CFIs stay per original section document.
- When the viewport comes within one screen of the last slot's end, preload/append the next section; when within
  one screen of the first slot's start, prepend the previous one and add its height to `scrollTop` in the same
  task (manual scroll anchoring, so content does not jump). Evict slots more than one section away from the current
  one, subtracting the height of slots removed above the viewport from `scrollTop`.
- Font/layout changes re-render all slots, then restore the anchor range of the current slot.
- Effort estimate: 3–4 days including instrumented tests (fling across a boundary shows the next heading without
  another gesture; CFI round-trip at a slot boundary; no jump when prepending).

## Questions for the gatekeeper

None. (Turn animation across boundaries is not a Must; noted above.)
