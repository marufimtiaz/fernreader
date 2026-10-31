# Reader bridge protocol (v1)

The reader runs in a single WebView that loads the bundled host page
(`https://appassets.androidplatform.net/assets/reader/index.html`, built from `reader-web/`).
Kotlin and JS exchange JSON text messages in both directions.

## Transport

- Kotlin to JS: `webView.evaluateJavascript("window.__reader.receive(<json string literal>)")` (main thread).
- JS to Kotlin: `ReaderBridge.postMessage(json)`, a `WebViewCompat.addWebMessageListener` object registered only for
  the origin `https://appassets.androidplatform.net`. Messages from any other origin never reach Kotlin.
- Types: `reader-web/src/bridge/protocol.ts` and `ui/.../reader/bridge/Protocol.kt` (`ToWeb`, `FromWeb`).

## Envelope

Every message is a JSON object `{ "v": 1, "type": "<name>", ...fields }`.

## Versioning

- Adding a field or a message type is compatible. Receivers ignore unknown fields and unknown `type` values
  (Kotlin `decodeFromWeb` returns null; TS `Bridge.receive` logs and drops). Neither side may throw on them.
- Bump `v` only for breaking changes, and keep supporting N-1.

## Messages (v1, slice subset)

Every book-scoped message carries `sessionId`; the receiving side ignores messages whose `sessionId` is not the
current session.

| Direction | type | Fields |
|---|---|---|
| app to web | `ping` | `id` |
| app to web | `open` | `sessionId`, `position` (virtual CFI or null), `texts.missingPart`, `settings` (as `setSettings`; optional) |
| app to web | `setSettings` | `settings {fontScale, fontFamily, lineHeight, marginPercent, align: publisher\|start\|justify, theme: light\|dark\|black\|warm, flow: paginated\|scrolled, swipeDirection: auto\|ltr\|rtl, pageAnimation: slide\|none}` (M4; AUTO flow already resolved by the app; `swipeDirection`/`pageAnimation` M6, optional, default auto/slide; `auto` = the book's page-progression-direction, resolved on the web side so `open` already uses it) |
| app to web | `close`, `next`, `prev` | none |
| app to web | `goTo` | `target` (CFI or href), `id` (optional integer: ask for `seeked`) |
| app to web | `goToText` | `sectionIndex`, `offset`, `length` (UTF-16, in the section's extracted text), `needle` (the hit text) (M5, F-050) |
| app to web | `clearSearchHighlight` | none |
| app to web | `back` | none (M6, F-042: back to where the last followed link was tapped; sent only while `history.depth` > 0) |
| app to web | `closeFootnote` | none (M6, F-043: closes the footnote popup) |
| app to web | `setBookmarks` | `sessionId`, `cfis[]` (M6, F-041: the book's bookmarks as virtual CFIs; sent after `opened` and whenever the list changes) |
| app to web | `addAnnotation` | `sessionId`, `id`, `cfi` (virtual range CFI), `color` (`yellow`\|`green`\|`blue`\|`pink`\|`orange`\|`purple`), `style` (`highlight`\|`underline`) (M6, F-060: draw a user annotation; the same `id` again redraws it) |
| app to web | `clearSelection` | none (M6, F-060: remove the text selection after a selection menu action) |
| web to app | `ready`, `pong` | `engine` / `id` |
| web to app | `opened` | `sessionId`, `identifier` (unique-identifier text or null), `title`, `authors[]`, `dir`, `fixedLayout`, `sections`, `toc[] {label, href, depth, missing, index}` (`index`: spine section of the target, -1 if none; M5) |
| web to app | `relocate` | `sessionId`, `cfi`, `fraction`, `sectionIndex`, `tocHref`, `page`/`pages` (paged: 1-based text page in the section and its text pages; 0/0 scrolled), `sectionFraction` (M4), `bookmarks[]` (M6, optional: the `setBookmarks` entries on the visible page, same strings; omitted when none) |
| web to app | `painted` | `sessionId` (two animation frames after `relocate`) |
| web to app | `seeked` | `sessionId`, `id` (after the `relocate` of a `goTo` that carried `id`) |
| web to app | `tap` | `sessionId`, `x`, `y` (fractions of the viewport; links and selections excluded) |
| web to app | `history` | `sessionId`, `depth` (M6, F-042: entries in the followed-links history, after every push and every `back`) |
| web to app | `footnote` | `sessionId`, `open` (M6, F-043: the popup opened, or closed on the web side or by `closeFootnote`) |
| web to app | `pageBookmarks` | `sessionId`, `cfis[]` (M6, F-041: answer to `setBookmarks`, the entries on the page showing now; empty while a seek is running) |
| web to app | `externalLink` | `sessionId`, `href` (M6, F-044: a tapped http, https or mailto link, normalised; other schemes are never sent) |
| web to app | `selection` | `sessionId`, `text`, `cfi` (virtual range CFI, or null), `sectionIndex` (M6, F-060: the selection settled; cleared = `text` "", `cfi` null, `sectionIndex` -1) |
| web to app | `error` | `sessionId` (or null), `kind` (`open_failed`, `nothing_readable`, or `hit_not_found`: not fatal, see below), `message` |

Ordering: `opened` is sent after the package is parsed and before the first page is displayed, so the app records
the book before the first `relocate` arrives. Reopening with the same `sessionId` as the last closed book reuses the
parsed book and its laid-out view, kept hidden since `close` (warm reopen, P-016): the view moves only if `position`
differs from where it is, and `opened`, `relocate`, `painted` follow as for any open. Opening another book releases it. Missing content documents are shown as a placeholder page (F-084); TOC entries
whose target is absent carry `missing: true` (R-001). Book scripts never run (CSP `script-src 'self'`, R-007).

### Seeks and stored positions (F-037, R-009)

- `next`, `prev` and `goTo` that arrive while a book is still opening (parse, first layout, initial seek) are queued
  and applied, in order, once the open has settled. foliate ignores navigation while its first section loads, so a
  `goTo` sent right after `opened` would otherwise be lost.
- While the view seeks to a requested position (the `open` position, a warm reopen that moves, or a `goTo`), the
  locations foliate reports on the way (e.g. the section start before the anchor) are not sent. Exactly one
  `relocate` is sent when the seek lands: the landed location, or where the view stayed if the target did not
  resolve. So the first `relocate` of an open is never a transient one.
- A `goTo` with `id` is answered with `seeked {sessionId, id}` after that `relocate` (also when the target did not
  resolve). The app uses it for the cross-source resume: when `opened` identifies a book that has a saved position
  but was opened without it (known under another source), the app sends `goTo {target: saved, id}` and stores no
  position until `seeked` with that id arrives. The book-start `relocate`s before it never replace the saved one.

### Search hits (M5, F-050)

- `goToText` is queued and sequenced like `goTo` (same `seeking` rule: exactly one `relocate` when it lands). The web
  side reads the section's text (Text and CDATA nodes under `body`, `script`/`style`/`template` excluded), checks that
  `needle` (compared lowercase and without diacritics, char by char) is at `offset`, else takes the nearest match
  within ±500 chars, and jumps to that range. The range gets a temporary highlight (search colour, drawn again when
  the section is re-laid out or preloaded) that stays until the next `goToText` or `clearSearchHighlight`.
- If the text is not found (or the section has no document), the view goes to the section start and the web side sends
  `error {kind: "hit_not_found"}` after the `relocate`. The app treats it as a notice, not as a failed reader.

## Rule for new messages

Every new message gets a fixture in `bridge/fixtures/` and a test on both sides
(`reader-web/test/bridge.test.ts`, `ui/src/jvmTest/.../ProtocolFixtureTest.kt`).

### Page-turn input (M6, F-032)

Every page turn goes through the web side's turn queue (`next`/`prev`, at most 3 pending, issued after the previous
turn finished and after the open settled), whatever its source: bridge messages (tap zones and volume keys, decided in
Kotlin from `tap` x and the key events, mirrored for an rtl book like swipes) and swipes. Swipes are recognised on the
web side: with the `host-swipe` attribute the patched paginator does not drag the page; it reports each finished
one-finger gesture in paginated flow as a `swipe` DOM event (`dx`, `dy`, `dt`), and `swipe.ts` turns it into at most
one turn (ltr: finger moving left = next; rtl: finger moving right = next; vertical writing also: up = next). A
swipe while text is selected turns nothing. Scrolled flow keeps native scrolling. `pageAnimation: none` removes
foliate's `animated` attribute (instant turns).

### Links, Back history, footnotes (M6, F-042..F-044)

- foliate's link handling (view.js) finds the tapped `a[href]` and emits `link` (in the book) or `external-link`; the
  host cancels both (foliate never navigates or opens a window itself) and decides. A tap on a link is never a `tap`.
- Note references (`epub:type` noteref/biblioref/glossref, `doc-noteref` roles, superscript links that are not
  backlinks, or a link to an element marked as a footnote/endnote/note in a section already loaded) open the
  footnote popup when the target can be found: the note's section document is parsed (never rendered, no scripts),
  its content copied through an allow-list (`links.ts` sanitizeNote: plain markup, no attributes but dir/lang, images
  as alt text, links keep only their resolved target), and shown in an overlay in the host page, themed like the
  page. It sends `footnote {open: true}`; a tap outside or `closeFootnote` closes it (`footnote {open: false}`). A
  link inside it: in the book, the popup closes and the link is followed; external, as below.
- Any other in-book link is followed: the exact current location (CFI) is pushed on the followed-links history, then
  the jump is queued like a `goTo` (one `relocate` when it lands). `back` pops the newest entry and goes there. Every
  push and every `back` (also one with nothing to pop) is answered by `history {depth}`, so the app can decide Back
  synchronously (R-005). TOC, search and slider jumps push nothing. The history starts empty with every `open`.
- `externalLink` carries only http(s) URLs with a host and mailto; javascript:, file:, content:, intent:, data: and
  every other scheme are dropped on the web side (and again in the app). The app asks first (setting
  `confirmExternalLinks`, a dialog naming the host or address) and then opens an ACTION_VIEW intent.

### Bookmarks (M6, F-041)

- The app owns the bookmarks (database, original CFIs); the web side only decides which are on the visible page. The
  app sends them as virtual CFIs (`setBookmarks`, replaces the whole list); the web side keeps the list for the session
  (cleared by every `open` and `close`) and puts the matching strings into each `relocate` (`bookmarks`) and, on
  `setBookmarks`, into a `pageBookmarks` answer for the page already showing. Strings, not indices, so a list that
  changed meanwhile can never be misread.
- "On the page": the page's location (foliate's relocate CFI, a range from the first to the last visible position) and
  the bookmark's own start, compared with epubcfi `compare`: start <= bookmark < end (`reader.ts` `visibleBookmarks`,
  `bookmarks.ts`). The end belongs to the next page, so a bookmark is on one page only. After a layout change the
  same rule puts the bookmark on whatever page now holds its position. O(bookmarks) per relocate; no layout work.
- The app stores the page's `relocate` CFI as it is (a range). A jump to it lands on that page; a bare point at a line
  break would resolve to the end of the previous line, one page early (a collapsed range has upstream affinity).

### Selection menu and annotations (M6, F-060, F-051)

- The browser's own selection is used: long-press selects a word with the WebView's native handles. The web side
  listens to `selectionchange` in every section document and, 120 ms after the last change, reads the first loaded
  section holding a non-collapsed selection: `text` = exactly `Selection.toString()` of that document (no trimming),
  `cfi` = `view.getCFI(index, range)`. It sends `selection` only when that differs from the last report; a collapse
  after a selection is sent once as cleared. Reset by every `open` and `close`.
- A tap that only dismisses a selection (it was there at `pointerdown`; the browser collapses it before the `click`)
  is not a `tap`, so it neither toggles the controls nor turns a page. A swipe while text is selected turns nothing.
- The menu is the WebView's floating action mode, wrapped in the app (`ReaderWebView.startActionMode`): Highlight,
  Underline, Search in book, Copy, Share first, then the system's text actions (PROCESS_TEXT activities, the smart
  text-assist item); the WebView's own Copy, Share, Select all and Web search are hidden. Each of our items acts on
  the app's last `selection`, then the app sends `clearSelection` and finishes the action mode.
- Copy puts `text` on the clipboard unchanged (leading/trailing whitespace as the browser selection gives it). Search
  in book runs F-050 with `text` trimmed, whitespace runs collapsed, at most 200 characters.
- Highlight/Underline: the app stores the annotation (original CFI, original spine index, quoted text with whitespace
  collapsed, current chapter label, last-used colour) and sends `addAnnotation`. The web side keeps the session's
  annotations by CFI and draws them through foliate's overlayer (`Overlayer.highlight`, 0.3 opacity; or
  `Overlayer.underline`, 2 px), again whenever their section gets a new overlayer. A colour row then lets one more tap
  recolour it (same `id`, new `addAnnotation`); the chosen colour becomes the default (`ReaderSettings.highlightColor`).

### Display settings (M4)

`setSettings` changes CSS custom properties and `data-er-*` attributes on each section's `<html>` only (one style
sheet injected per document, `reader-web/src/reader/styles.ts`), so a change reflows without rewriting styles
(P-020). Side margins m % per side map to foliate's `gap` attribute (2m %). The paginator keeps the first visible
range as its anchor, so a reflow keeps the passage (F-070). Preloaded neighbour sections get the same settings.

