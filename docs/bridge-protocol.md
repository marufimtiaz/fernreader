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

## Messages (v0)

| Direction | type | Fields | Meaning |
|---|---|---|---|
| web to app | `ready` | `engine: string` | Host page loaded; module graph is warm. |
| app to web | `ping` | `id: number` | Liveness check. |
| web to app | `pong` | `id: number` | Reply to `ping`, same `id`. |
| web to app | `error` | `kind: string`, `message: string` | Reader-side failure. |

## Rule for new messages

Every new message gets a fixture in `bridge/fixtures/` and a test on both sides
(`reader-web/test/bridge.test.ts`, `ui/src/jvmTest/.../ProtocolFixtureTest.kt`).
