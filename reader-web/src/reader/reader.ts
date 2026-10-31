import '../../vendor/foliate-js/view.js'
import { EPUB } from '../../vendor/foliate-js/epub.js'
import type { Bridge } from '../bridge/bridge'
import { makeLoader } from './loader'
import { flattenToc } from './toc'

/* foliate-js is untyped JS; keep `any` at this boundary only. */
type Book = any
/** The last closed book: parsed, and its view kept laid out but hidden, for a same-session reopen (P-016). */
type Warm = { sessionId: string; book: Book; has: (n: string) => boolean; view: any }

/** foliate metadata values may be strings, language maps or contributor objects. */
const text = (x: unknown): string =>
  typeof x === 'string' ? x
    : x && typeof x === 'object' ? ('name' in x ? text((x as { name: unknown }).name) : text(Object.values(x)[0])) : ''

export class Reader {
  private view: any = null
  private book: Book = null
  private has: ((n: string) => boolean) | null = null
  private sessionId: string | null = null
  private warm: Warm | null = null
  private token = 0

  constructor(private bridge: Bridge) {
    bridge.onMessage(m => {
      switch (m.type) {
        case 'open': void this.open(m.sessionId, m.position, m.texts.missingPart); break
        case 'close': this.close(); break
        case 'next': void this.view?.next(); break
        case 'prev': void this.view?.prev(); break
        case 'goTo': this.view?.goTo(m.target)?.catch?.((e: unknown) => console.warn('goTo failed', e)); break
      }
    })
  }

  private async open(sessionId: string, position: string | null, missingPart: string) {
    this.close()
    this.sessionId = sessionId
    const token = ++this.token
    const stale = () => this.token !== token
    try {
      const warm = this.warm
      this.warm = null
      if (warm?.sessionId === sessionId && warm.view?.lastLocation) {
        await this.reopen(warm, sessionId, position, stale)
        return
      }
      if (warm?.view) destroyView(warm.view) // only one warm book (P-060)
      if (warm?.sessionId === sessionId) {
        this.book = warm.book; this.has = warm.has
      } else {
        const loader = await makeLoader(`/book/${encodeURIComponent(sessionId)}`, missingPart)
        const book = await new EPUB(loader as any).init()
        if (stale()) return
        this.book = book
        this.has = loader.has
      }
      if (stale()) return
      const book = this.book
      if (!book.sections?.length) {
        this.book = null; this.has = null
        this.bridge.send({ v: 1, type: 'error', sessionId, kind: 'nothing_readable', message: 'The book has no readable sections.' })
        return
      }
      this.sendOpened(sessionId)
      const view = document.createElement('foliate-view') as any
      this.view = view
      document.body.append(view)
      view.addEventListener('relocate', (e: CustomEvent) => this.onRelocate(sessionId, e.detail))
      view.addEventListener('load', (e: CustomEvent) => this.onLoad(sessionId, e.detail.doc))
      view.addEventListener('external-link', (e: Event) => e.preventDefault()) // F-044 arrives in M6
      await view.open(book)
      if (stale()) return
      const r = view.renderer
      r.setAttribute('flow', 'paginated')
      r.setAttribute('animated', '')
      r.setAttribute('margin', '24px')
      r.setAttribute('gap', '6%')
      r.setAttribute('max-column-count', '1')
      // A position that no longer resolves (e.g. the file changed) falls back to the start (C-009 refinement: M3).
      try { await view.init({ lastLocation: position }) } catch { if (!stale()) await view.init({}) }
    } catch (e) {
      if (!stale()) {
        this.book = null; this.has = null
        if (this.view) { destroyView(this.view); this.view = null }
        this.bridge.send({ v: 1, type: 'error', sessionId, kind: 'open_failed', message: String((e as Error)?.message ?? e) })
      }
    }
  }

  /**
   * Warm reopen: the kept view is shown again where it was; it moves only if the requested position differs.
   * Sends the same opened → relocate → painted sequence as a full open.
   */
  private async reopen(warm: Warm, sessionId: string, position: string | null, stale: () => boolean) {
    this.book = warm.book; this.has = warm.has; this.view = warm.view
    this.sendOpened(sessionId)
    warm.view.style.visibility = ''
    if (position && position !== warm.view.lastLocation?.cfi) {
      // A position that no longer resolves stays where the view is (foliate's goTo logs and resolves undefined).
      if (await warm.view.goTo(position)) return // its relocate event reports the new location
      if (stale()) return
    }
    this.onRelocate(sessionId, warm.view.lastLocation)
  }

  private sendOpened(sessionId: string) {
    const book = this.book
    this.bridge.send({
      v: 1, type: 'opened', sessionId,
      identifier: book.metadata?.identifier || null,
      title: text(book.metadata?.title),
      authors: [book.metadata?.author ?? []].flat().map(text).filter(Boolean),
      dir: book.dir === 'rtl' ? 'rtl' : 'ltr',
      fixedLayout: book.rendition?.layout === 'pre-paginated',
      sections: book.sections.length,
      toc: flattenToc(book, this.has!),
    })
  }

  private onRelocate(sessionId: string, d: any) {
    if (this.sessionId !== sessionId) return
    this.bridge.send({
      // foliate reports NaN while a section has no layout yet; JSON turns NaN into null, which Kotlin rejects.
      v: 1, type: 'relocate', sessionId, cfi: d.cfi ?? '', fraction: Number.isFinite(d.fraction) ? d.fraction : 0,
      sectionIndex: d.section?.current ?? 0, tocHref: d.tocItem?.href ?? null,
    })
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (this.sessionId === sessionId) this.bridge.send({ v: 1, type: 'painted', sessionId })
    }))
  }

  /** Center-tap detection for the chrome (F-033). Swipes are handled by foliate's paginator itself. */
  private onLoad(sessionId: string, doc: Document) {
    doc.addEventListener('click', (e: MouseEvent) => {
      if (this.sessionId !== sessionId) return
      if ((e.target as Element | null)?.closest?.('a[href]')) return
      if (doc.getSelection && !doc.getSelection()!.isCollapsed) return
      const frame = doc.defaultView?.frameElement as HTMLElement | null
      const box = frame?.getBoundingClientRect()
      this.bridge.send({
        v: 1, type: 'tap', sessionId,
        x: ((box?.left ?? 0) + e.clientX) / window.innerWidth,
        y: ((box?.top ?? 0) + e.clientY) / window.innerHeight,
      })
    })
  }

  /**
   * Keeps the parsed book and its laid-out view, hidden (visibility keeps the layout), for a same-session reopen
   * (P-016). Its events are dropped while closed (sessionId is null). A view that never reached a location is released.
   */
  private close() {
    this.token++ // invalidates any open still in flight
    const view = this.view
    if (view && !view.lastLocation) { destroyView(view) }
    if (this.book && this.sessionId && this.has) {
      const kept = view?.lastLocation ? view : null
      if (kept) kept.style.visibility = 'hidden'
      this.warm = { sessionId: this.sessionId, book: this.book, has: this.has, view: kept }
    } else if (view?.lastLocation) destroyView(view)
    this.view = null; this.book = null; this.has = null; this.sessionId = null
  }
}

function destroyView(view: any) {
  try { view.close?.() } catch { /* already broken */ }
  view.remove()
}
