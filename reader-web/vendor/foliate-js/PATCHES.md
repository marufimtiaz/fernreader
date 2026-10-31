# foliate-js vendored copy

Upstream: https://github.com/johnfactotum/foliate-js, commit 78914aef4466eb960965702401634c2cb348e9b1 (2026-05-01), MIT.
Only first-party modules are vendored (view, epub, epubcfi, paginator, fixed-layout, progress, overlayer, search,
text-walker, footnotes). Every local change is listed below with the reason and the commit that made it.

| Date | File | Change | Reason |
|---|---|---|---|
| 2026-10-31 | paginator.js | `export class View`; `layout`, `styles`, `busy` getters | page counter lays out sections exactly like the paginator (M4 Tasks 8-9); counter waits while a turn runs |
| 2026-10-31 | paginator.js | Neighbour preloading behind the `preload` attribute (slot cache, instant boundary swap, eviction beyond ±1, `getContents()` includes loaded hidden views), ported from the M0 S2 spike; neighbours with another writing mode are not preloaded; no swap before the first view exists | P-026 (boundary turn ≤ 400 ms, no blank frame); highlights/search hits drawn into preloaded sections (S2 bug) |
| 2026-10-31 | paginator.js | Stack mode (scrolled flow + `preload` + horizontal writing): slots stacked in reading order in one scroller, the section at the top of the viewport is current, offsets relative to it, slot insert/remove keeps the on-screen position, browser scroll anchoring off; flow switch re-lays out neighbours | F-031: scrolling continues across chapter boundaries |
| 2026-10-31 | paginator.js | `#createView` finds the current slot by its view (not `#index`, already the target in `#display`) | spike bug: a jump left the old section's slot cached with a destroyed view (blank swap, leak) |
| 2026-10-31 | paginator.js | `debugSlots` getter | diagnostics for instrumented tests |
| 2026-10-31 | paginator.js | `pagedLayout` getter and a hidden `#probe` grid item where the paged `#container` sits | the page counter uses the paged layout in scrolled mode too (C-002) |
| 2026-11-01 | paginator.js | `host-swipe` attribute: in paginated flow the page no longer follows the finger (touchmove still `preventDefault`s); a finished one-finger, unpinched gesture is dispatched as a `swipe` CustomEvent `{dx, dy, dt}` (finger displacement in CSS px from touchstart, ms) instead of `scrollBy`/`snap`; a touchmove without a touchstart is ignored. Without the attribute foliate's own drag/snap is unchanged | F-032/C-026: every swipe moves exactly one page through the host's turn queue (a swipe during the 300 ms turn animation was clamped to the same page by `#scrollBounds`, one before the first `#scrollTo` threw) |
