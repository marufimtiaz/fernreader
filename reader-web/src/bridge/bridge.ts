import { TO_WEB_TYPES, type FromWeb, type ToWeb } from './protocol'

const isStr = (x: unknown) => typeof x === 'string'

const isNum = (x: unknown) => typeof x === 'number' && Number.isFinite(x)
function validSettings(x: unknown): boolean {
  const s = x as Record<string, unknown> | null
  return !!s && typeof s === 'object' && isNum(s.fontScale) && isNum(s.lineHeight) && isNum(s.marginPercent) &&
    isStr(s.fontFamily) && ['publisher', 'start', 'justify'].includes(s.align as string) &&
    ['light', 'dark', 'black', 'warm'].includes(s.theme as string) && ['paginated', 'scrolled'].includes(s.flow as string) &&
    (s.swipeDirection == null || ['auto', 'ltr', 'rtl'].includes(s.swipeDirection as string)) &&
    (s.pageAnimation == null || ['slide', 'none'].includes(s.pageAnimation as string)) &&
    (s.chapters == null || ['continuous', 'one-at-a-time'].includes(s.chapters as string)) &&
    (s.topInset == null || (typeof s.topInset === 'number' && s.topInset >= 0)) &&
    (s.dim == null || (isNum(s.dim) && (s.dim as number) > 0 && (s.dim as number) <= 1)) // C-110
}

function validAnnotation(x: unknown): boolean {
  const a = x as Record<string, unknown> | null
  return !!a && typeof a === 'object' && isStr(a.id) && isStr(a.cfi) && isStr(a.color) && ['highlight', 'underline'].includes(a.style as string)
}

/** Per-type shape check for messages from Kotlin; a malformed message is dropped, never dispatched. */
function validShape(m: Record<string, unknown>): boolean {
  switch (m.type) {
    case 'open': {
      const t = m.texts as Record<string, unknown> | null | undefined
      const chapterTexts = ['nextChapter', 'previousChapter', 'nextChapterUntitled', 'previousChapterUntitled'] // C-085, optional
      return isStr(m.sessionId) && !!t && typeof t === 'object' && isStr(t.missingPart) &&
        chapterTexts.every(k => t[k] == null || isStr(t[k])) &&
        (m.continued == null || (Array.isArray(m.continued) && (m.continued as unknown[]).every(i => Number.isInteger(i) && (i as number) > 0))) &&
        (m.position == null || isStr(m.position)) && (m.settings == null || validSettings(m.settings))
    }
    case 'goTo': return isStr(m.target) && (m.id == null || Number.isInteger(m.id))
    case 'goToText': return Number.isInteger(m.sectionIndex) && (m.sectionIndex as number) >= 0 && Number.isInteger(m.offset) &&
      (m.offset as number) >= 0 && Number.isInteger(m.length) && (m.length as number) >= 0 && isStr(m.needle)
    case 'goToOffset': return Number.isInteger(m.sectionIndex) && (m.sectionIndex as number) >= 0 && Number.isInteger(m.offset) && (m.offset as number) >= 0
    case 'textOffsets': return isStr(m.sessionId) && Number.isInteger(m.id) && Array.isArray(m.cfis) && (m.cfis as unknown[]).every(isStr)
    case 'setBookmarks': return isStr(m.sessionId) && Array.isArray(m.cfis) && (m.cfis as unknown[]).every(isStr)
    case 'addAnnotation': return isStr(m.sessionId) && validAnnotation(m)
    case 'setAnnotations': return isStr(m.sessionId) && Array.isArray(m.items) && (m.items as unknown[]).every(validAnnotation)
    case 'removeAnnotation': return isStr(m.sessionId) && isStr(m.id)
    case 'setSettings': return validSettings(m.settings)
    case 'countPages': return isStr(m.sessionId) && Number.isInteger(m.gen) && typeof m.run === 'boolean'
    case 'goToFraction': return isNum(m.fraction) && (m.fraction as number) >= 0 && (m.fraction as number) <= 1 && (m.id == null || Number.isInteger(m.id))
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
