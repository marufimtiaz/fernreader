import type { FromWeb, ToWeb } from './protocol'

export type Post = (json: string) => void

/** Typed JSON bridge. Kotlin → JS: window.__reader.receive(json). JS → Kotlin: ReaderBridge.postMessage(json). */
export class Bridge {
  private handlers: Array<(m: ToWeb) => void> = []
  constructor(private post: Post) {}

  send(m: FromWeb) { this.post(JSON.stringify(m)) }

  onMessage(h: (m: ToWeb) => void) { this.handlers.push(h) }

  receive(json: string) {
    let m: { v?: number; type?: string; [k: string]: unknown }
    try { m = JSON.parse(json) } catch { return }
    switch (m.type) {
      case 'ping':
        this.send({ v: 1, type: 'pong', id: Number(m.id) })
        return
      default:
        if (!['ping'].includes(String(m.type))) { console.warn('bridge: ignoring unknown message', m.type); return }
    }
    for (const h of this.handlers) h(m as ToWeb)
  }
}
