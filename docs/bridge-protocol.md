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
| app to web | `open` | `sessionId`, `position` (virtual CFI or null), `texts.missingPart` |
| app to web | `close`, `next`, `prev` | none |
| app to web | `goTo` | `target` (CFI or href) |
| web to app | `ready`, `pong` | `engine` / `id` |
| web to app | `opened` | `sessionId`, `identifier` (unique-identifier text or null), `title`, `authors[]`, `dir`, `fixedLayout`, `sections`, `toc[] {label, href, depth, missing}` |
| web to app | `relocate` | `sessionId`, `cfi`, `fraction`, `sectionIndex`, `tocHref` |
| web to app | `painted` | `sessionId` (two animation frames after `relocate`) |
| web to app | `tap` | `sessionId`, `x`, `y` (fractions of the viewport; links and selections excluded) |
| web to app | `error` | `sessionId` (or null), `kind` (`open_failed` or `nothing_readable`), `message` |

Ordering: `opened` is sent after the package is parsed and before the first page is displayed, so the app records
the book before the first `relocate` arrives. Reopening with the same `sessionId` as the last closed book reuses the
parsed book (warm reopen, P-016). Missing content documents are shown as a placeholder page (F-084); TOC entries
whose target is absent carry `missing: true` (R-001). Book scripts never run (CSP `script-src 'self'`, R-007).

## Rule for new messages

Every new message gets a fixture in `bridge/fixtures/` and a test on both sides
(`reader-web/test/bridge.test.ts`, `ui/src/jvmTest/.../ProtocolFixtureTest.kt`).
