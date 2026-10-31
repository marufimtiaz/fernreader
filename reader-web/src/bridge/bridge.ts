import { TO_WEB_TYPES, type FromWeb, type ToWeb } from './protocol'

const isStr = (x: unknown) => typeof x === 'string'

/** Per-type shape check for messages from Kotlin; a malformed message is dropped, never dispatched. */
function validShape(m: Record<string, unknown>): boolean {
  switch (m.type) {
    case 'open': {
      const t = m.texts as { missingPart?: unknown } | null | undefined
      return isStr(m.sessionId) && !!t && typeof t === 'object' && isStr(t.missingPart) &&
        (m.position == null || isStr(m.position))
    }
    case 'goTo': return isStr(m.target) && (m.id == null || Number.isInteger(m.id))
    default: return true
  }
}

export type Post = (json: string) => void

/** Typed JSON bridge. Kotlin → JS: window.__reader.receive(json). JS → Kotlin: ReaderBridge.postMessage(json). */
export class Bridge {
  private handlers: Array<(m: ToWeb) => void> = []
  constructor(private post: Post) {}

  send(m: FromWeb) { this.post(JSON.stringify(m)) }

  onMessage(h: (m: ToWeb) => void) { this.handlers.push(h) }

  receive(json: string) {
    let parsed: unknown
    try { parsed = JSON.parse(json) } catch { return }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return
    const m = parsed as { type?: unknown; id?: unknown }
    if (!TO_WEB_TYPES.includes(m.type as ToWeb['type'])) { console.warn('bridge: ignoring unknown message', m.type); return }
    if (m.type === 'ping') { this.send({ v: 1, type: 'pong', id: Number(m.id) }); return }
    if (!validShape(parsed as Record<string, unknown>)) { console.warn('bridge: ignoring malformed', m.type); return }
    for (const h of this.handlers) {
      try { h(m as ToWeb) } catch (e) { console.warn('bridge: handler failed', e) }
    }
  }
}
