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
| app to web | `setSettings` | `settings {fontScale, fontFamily, lineHeight, marginPercent, align: publisher\|start\|justify, theme: light\|dark\|black\|warm, flow: paginated\|scrolled}` (M4; AUTO flow already resolved by the app) |
| app to web | `close`, `next`, `prev` | none |
| app to web | `goTo` | `target` (CFI or href), `id` (optional integer: ask for `seeked`) |
| web to app | `ready`, `pong` | `engine` / `id` |
| web to app | `opened` | `sessionId`, `identifier` (unique-identifier text or null), `title`, `authors[]`, `dir`, `fixedLayout`, `sections`, `toc[] {label, href, depth, missing}` |
| web to app | `relocate` | `sessionId`, `cfi`, `fraction`, `sectionIndex`, `tocHref`, `page`/`pages` (paged: 1-based text page in the section and its text pages; 0/0 scrolled), `sectionFraction` (M4) |
| web to app | `painted` | `sessionId` (two animation frames after `relocate`) |
| web to app | `seeked` | `sessionId`, `id` (after the `relocate` of a `goTo` that carried `id`) |
| web to app | `tap` | `sessionId`, `x`, `y` (fractions of the viewport; links and selections excluded) |
| web to app | `error` | `sessionId` (or null), `kind` (`open_failed` or `nothing_readable`), `message` |

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

## Rule for new messages

Every new message gets a fixture in `bridge/fixtures/` and a test on both sides
(`reader-web/test/bridge.test.ts`, `ui/src/jvmTest/.../ProtocolFixtureTest.kt`).

### Display settings (M4)

`setSettings` changes CSS custom properties and `data-er-*` attributes on each section's `<html>` only (one style
sheet injected per document, `reader-web/src/reader/styles.ts`), so a change reflows without rewriting styles
(P-020). Side margins m % per side map to foliate's `gap` attribute (2m %). The paginator keeps the first visible
range as its anchor, so a reflow keeps the passage (F-070). Preloaded neighbour sections get the same settings.

