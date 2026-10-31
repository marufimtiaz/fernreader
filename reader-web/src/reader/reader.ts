import '../../vendor/foliate-js/view.js'
import { EPUB } from '../../vendor/foliate-js/epub.js'
import { Overlayer } from '../../vendor/foliate-js/overlayer.js'
import type { Bridge } from '../bridge/bridge'
import { makeLoader } from './loader'
import { flattenToc } from './toc'
import { resolveSaved } from './position'
import { PageCounter } from './counter'
import { shareSections } from './sections'
import { resolveTextRange } from './textpos'
import { effectiveDir, swipeToTurn } from './swipe'
import { applySettings as applyStyles, DEFAULT_SETTINGS, readerCss, THEMES, type WebSettings } from './styles'

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
  /** Resolves when the current open (parse, layout, seek) has finished; queued commands wait for it. */
  private settled: Promise<void> = Promise.resolve()
  /**
   * Pending turns and jumps, issued one at a time after the open has settled: foliate ignores a turn or a goTo while
   * one is animating or while the first section is still loading (F-030, F-037).
   */
  private queue: Cmd[] = []
  private pumping = false
  /**
   * True while the view seeks to a requested position (saved position on open, warm reopen move, goTo). foliate may
   * report intermediate locations meanwhile (e.g. the section start before the anchor); they are not sent, only the
   * location where the seek lands (bridge-protocol.md, R-009).
   */
  private seeking = false
  /** Display settings for every view (the open one, the warm one, preloaded sections). */
  private settings: WebSettings = DEFAULT_SETTINGS
  private readonly css = readerCss(new URL('./', location.href).href)
  /** Page counts for the open book (F-034); one generation per layout. */
  private counter: PageCounter | null = null
  private countGen = 0
  private countTimer: ReturnType<typeof setTimeout> | null = null
  /** The one temporary search highlight (F-050): its CFI and section, drawn again whenever that section's overlayer is made. */
  private search: { cfi: string; index: number } | null = null

  constructor(private bridge: Bridge) {
    // Read-only probes for the app's instrumented tests (evaluateJavascript); nothing in the app calls them.
    ;(window as any).__readerTest = {
      page: () => { const r = this.view?.renderer; return r ? { page: r.page, textPages: r.pages - 2, index: this.view.lastLocation?.section?.current } : null },
      /** Sections with a loaded document: the current one first, then preloaded neighbours. */
      loaded: () => this.view?.renderer?.getContents?.().map((c: any) => c.index) ?? [],
      firstParagraphCfi: (index: number) => {
        const c = this.view?.renderer?.getContents?.().find((x: any) => x.index === index)
        const p = c?.doc?.querySelector('p')
        if (!p) return null
        const r = c.doc.createRange(); r.selectNodeContents(p)
        return this.view.getCFI(index, r)
      },
      /** Draws a test highlight like a user highlight (F-061 path; S2 bug: preloaded sections must get it too). */
      decorate: (cfi: string) => {
        const v = this.view
        if (!v) return false
        if (!v.__testDraw) { v.__testDraw = true; v.addEventListener('draw-annotation', (e: CustomEvent) => e.detail.draw(Overlayer.highlight, { color: 'yellow' })) }
        void v.addAnnotation({ value: cfi })
        return true
      },
      lastPage: () => { const v = this.view; const i = v?.lastLocation?.section?.current; if (v && i != null) void v.renderer.goTo({ index: i, anchor: () => 1 }) },
      /** Text of the block element where the visible range starts (F-070 check). */
      firstVisibleBlock: () => {
        const r = this.view?.lastLocation?.range
        const n = r?.startContainer
        const el = (n?.nodeType === 1 ? n : n?.parentElement)?.closest?.('p, li, blockquote, h1, h2, h3, h4, h5, h6, div')
        return el?.textContent?.trim().slice(0, 80) ?? null
      },
      blockVisible: (text: string) => {
        const r = this.view?.lastLocation?.range
        const doc = r?.startContainer?.ownerDocument
        const el = [...(doc?.querySelectorAll('p, li, blockquote, h1, h2, h3, h4, h5, h6, div') ?? [])].find(e => e.textContent?.trim().startsWith(text))
        return !!el && r.intersectsNode(el)
      },
      firstBlockText: (index: number) => {
        const d = this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.doc
        return [...(d?.body?.querySelectorAll('h1, h2, h3, h4, p') ?? [])].map(e => e.textContent?.trim()).find(Boolean) ?? null
      },
      searchHighlight: () => this.search?.cfi ?? null,
      slots: () => this.view?.renderer?.debugSlots ?? null,
      linear: () => (this.book?.sections ?? []).map((x: any, i: number) => (x.linear !== 'no' ? i : -1)).filter((i: number) => i >= 0),
      goIndex: (i: number) => { void this.view?.renderer?.goTo({ index: i, anchor: 0 }) },
      htmlVar: (index: number, name: string) => this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.doc?.documentElement.style.getPropertyValue(name) ?? null,
      overlayCount: (index: number) => this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.overlayer?.element?.childElementCount ?? -1,
    }
    // A new viewport (rotation, window size) is a new layout: count again (F-034, P-022).
    window.addEventListener('resize', () => { if (this.view) this.scheduleCount() })
    bridge.onMessage(m => {
      switch (m.type) {
        case 'open':
          if (m.settings) this.settings = m.settings
          void this.open(m.sessionId, m.position, m.texts.missingPart); break
        case 'setSettings': this.setSettings(m.settings); break
        case 'goToFraction': this.enqueue({ kind: 'fraction', fraction: m.fraction }); break
        case 'close': this.close(); break
        case 'next': this.turn('next'); break
        case 'prev': this.turn('prev'); break
        case 'goTo': this.enqueue({ kind: 'goTo', target: m.target, id: m.id }); break
        case 'goToText': this.enqueue({ kind: 'text', index: m.sectionIndex, offset: m.offset, length: m.length, needle: m.needle }); break
        case 'clearSearchHighlight': this.enqueue({ kind: 'clearSearch' }); break
      }
    })
  }

  private async open(sessionId: string, position: string | null, missingPart: string) {
    this.close()
    this.sessionId = sessionId
    const token = ++this.token
    const stale = () => this.token !== token
    let settle!: () => void
    this.settled = new Promise(r => { settle = r })
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
        shareSections((book.sections ?? []).filter(Boolean) as any[]) // the paginator and the page counter share each section's resources
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
      view.addEventListener('relocate', (e: CustomEvent) => this.onRelocate(view, sessionId, e.detail))
      view.addEventListener('load', (e: CustomEvent) => { applySettings(e.detail.doc, this.settings); this.onLoad(sessionId, e.detail.doc) })
      view.addEventListener('draw-annotation', (e: CustomEvent) => {
        if (e.detail.annotation?.value === this.search?.cfi) e.detail.draw(Overlayer.highlight, { color: SEARCH_COLOR })
      })
      view.addEventListener('create-overlay', (e: CustomEvent) => { if (this.search && e.detail.index === this.search.index) this.drawSearch(view) })
      view.addEventListener('external-link', (e: Event) => e.preventDefault()) // F-044 arrives in M6
      await view.open(book)
      if (stale()) return
      const r = view.renderer
      this.layoutAttributes(r)
      // Swipes come to the host as `swipe` events and turn pages through the queue (F-032, C-026), not foliate's drag.
      r.setAttribute('host-swipe', '')
      r.addEventListener('swipe', (e: CustomEvent) => this.onSwipe(view, e.detail))
      r.setAttribute('max-column-count', '1')
      r.setStyles(this.css)
      r.setAttribute('preload', '') // neighbour sections laid out in advance: boundary turns swap instantly (P-026)
      if (!position) { await view.init({}); return }
      // A position that no longer resolves exactly (the file changed in place) lands on the nearest valid place in its
      // chapter (C-009); only a position whose chapter is gone falls back to the start.
      this.seeking = true
      try { if (!(await goToTarget(view, position, true))) await view.init({}) } catch { if (!stale()) await view.init({}) }
      if (stale()) return
      this.seeking = false
      if (view.lastLocation) this.onRelocate(view, sessionId, view.lastLocation)
    } catch (e) {
      if (!stale()) {
        this.book = null; this.has = null
        if (this.view) { destroyView(this.view); this.view = null }
        this.bridge.send({ v: 1, type: 'error', sessionId, kind: 'open_failed', message: String((e as Error)?.message ?? e) })
      }
    } finally {
      settle()
    }
  }

  /** At most [MAX_TURNS] pending; more are dropped (a burst of taps still moves that many pages, in order). */
  private turn(dir: 'next' | 'prev') {
    if (this.queue.filter(c => c.kind === 'turn').length >= MAX_TURNS) return
    this.enqueue({ kind: 'turn', dir })
  }

  /** Commands that arrive while the open is still loading wait for it (C1: the resume goTo after `opened`). */
  private enqueue(c: Cmd) {
    if (!this.sessionId) return
    this.queue.push(c)
    void this.pump()
  }

  private async pump() {
    if (this.pumping) return
    this.pumping = true
    const token = this.token
    try {
      await this.settled
      while (this.queue.length && this.token === token && this.view) {
        const view = this.view
        const sessionId = this.sessionId!
        const c = this.queue.shift()!
        if (c.kind === 'turn') {
          // Resolves after the turn (and foliate's lock) is over, so the next one is not ignored.
          try { await (c.dir === 'next' ? view.next() : view.prev()) } catch (e) { console.warn('turn failed', e) }
          continue
        }
        if (c.kind === 'clearSearch') { this.clearSearch(view); continue }
        let hit: { cfi: string; index: number } | null = null
        this.seeking = true
        try {
          if (c.kind === 'fraction') await view.goToFraction(c.fraction)
          else if (c.kind === 'text') {
            this.clearSearch(view)
            hit = await this.findHit(view, c)
            if (hit && !(await goToTarget(view, hit.cfi))) hit = null // the CFI did not resolve: same as not found
            if (!hit) await view.renderer.goTo({ index: c.index, anchor: 0 })
          } else await goToTarget(view, c.target)
        } catch (e) {
          console.warn('goTo failed', e)
          if (c.kind === 'text') { // a hit whose jump threw is a hit not found: no highlight, section start, error below
            hit = null
            try { await view.renderer.goTo({ index: c.index, anchor: 0 }) } catch (e2) { console.warn('goTo section start failed', e2) }
          }
        }
        if (this.token !== token || this.view !== view) break
        this.seeking = false
        // One relocate where it landed (or, if the target did not resolve, where it stayed), then the answer.
        if (view.lastLocation) this.onRelocate(view, sessionId, view.lastLocation)
        if (c.kind === 'goTo' && c.id != null) this.bridge.send({ v: 1, type: 'seeked', sessionId, id: c.id })
        if (c.kind === 'text') {
          if (hit) { this.search = hit; this.drawSearch(view) }
          else this.bridge.send({ v: 1, type: 'error', sessionId, kind: 'hit_not_found', message: 'The search result was not found in the section text.' })
        }
      }
    } finally {
      this.pumping = false
    }
    if (this.queue.length && this.token !== token) void this.pump() // queued for a newer open
  }

  /** The CFI of a search hit's text in its section, or null (no such section or document, or the text is not there). */
  private async findHit(view: any, c: Extract<Cmd, { kind: 'text' }>): Promise<{ cfi: string; index: number } | null> {
    try {
      const doc: Document | undefined = await this.book?.sections?.[c.index]?.createDocument?.()
      const range = doc && resolveTextRange(doc, c.offset, c.length, c.needle)
      return range ? { cfi: view.getCFI(c.index, range), index: c.index } : null
    } catch (e) { console.warn('hit resolution failed', e); return null }
  }

  private drawSearch(view: any) {
    if (this.search) void view.addAnnotation({ value: this.search.cfi }).catch?.((e: unknown) => console.warn('search highlight failed', e))
  }

  private clearSearch(view: any) {
    const s = this.search
    this.search = null
    if (s) void view.deleteAnnotation({ value: s.cfi })?.catch?.(() => {})
  }

  /**
   * Warm reopen: the kept view is shown again where it was; it moves only if the requested position differs.
   * Sends the same opened → relocate → painted sequence as a full open.
   */
  private async reopen(warm: Warm, sessionId: string, position: string | null, stale: () => boolean) {
    this.book = warm.book; this.has = warm.has; this.view = warm.view
    this.applyToView(warm.view) // settings may have changed while the book was closed
    this.sendOpened(sessionId)
    warm.view.style.visibility = ''
    if (position && position !== warm.view.lastLocation?.cfi) {
      // A position that no longer resolves stays where the view is (foliate's goTo logs and resolves undefined, or throws).
      this.seeking = true
      try { await goToTarget(warm.view, position) } catch (e) { console.warn('goTo failed', e) }
      if (stale()) return
      this.seeking = false
    }
    this.onRelocate(warm.view, sessionId, warm.view.lastLocation)
  }

  /** Side margins (F-072: m % per side = foliate gap 2m %), the flow mode, and the vertical margins (page indicator room). */
  private layoutAttributes(r: any) {
    const s = this.settings
    const set = (k: string, v: string) => { if (r.getAttribute(k) !== v) r.setAttribute(k, v) }
    set('margin', '32px')
    set('gap', `${2 * s.marginPercent}%`)
    set('flow', s.flow)
    if (s.pageAnimation === 'none') r.removeAttribute('animated') // F-032: no slide
    else set('animated', '')
    document.body.style.background = THEMES[s.theme]?.bg ?? ''
  }

  /** Settings on every loaded document of [view]; the paginator re-expands and keeps the first visible text (F-070). */
  private applyToView(view: any) {
    const r = view?.renderer
    if (!r) return
    const [current, ...neighbours] = r.getContents?.() ?? []
    if (current?.doc) applySettings(current.doc, this.settings)
    this.layoutAttributes(r)
    // Preloaded neighbours reflow right after the visible page has been painted, not in the same frame (P-020).
    const s = this.settings
    requestAnimationFrame(() => setTimeout(() => {
      for (const c of neighbours) if (c.doc && this.settings === s) applySettings(c.doc, s)
    }, 0))
  }

  private setSettings(s: WebSettings) {
    const old = this.settings
    this.settings = s
    const layout = (x: WebSettings) => [x.fontScale, x.fontFamily, x.lineHeight, x.marginPercent, x.align].join('|')
    if (layout(old) !== layout(s) && this.counter) this.scheduleCount()
    const look = (x: WebSettings) => [layout(x), x.theme, x.flow].join('|')
    if (this.view && look(old) === look(s)) this.layoutAttributes(this.view.renderer) // input settings only: nothing to restyle
    else if (this.view) this.applyToView(this.view)
    else document.body.style.background = THEMES[s.theme]?.bg ?? ''
  }

  private startCounter(sessionId: string) {
    const book = this.book
    if (!book || this.sessionId !== sessionId) return
    // The counter mirrors horizontal pagination only. A vertical book gets its section list (so the page number can
    // be shown from the text model, C-017) but no book-wide total yet (M4 known gap).
    const doc = this.view?.renderer?.getContents?.()[0]?.doc
    const mode = doc ? doc.defaultView?.getComputedStyle(doc.documentElement).writingMode ?? '' : ''
    if (mode.startsWith('vertical')) {
      const linear = book.sections.map((x: any, i: number) => (x?.linear !== 'no' ? i : -1)).filter((i: number) => i >= 0)
      this.bridge.send({ v: 1, type: 'pageCounts', sessionId, gen: ++this.countGen, linear, counts: [], sample: false, done: false })
      return
    }
    this.counter = new PageCounter(
      book.sections, () => this.view?.renderer ?? null, () => this.settings,
      String(book.metadata?.language ?? ''), book.dir === 'rtl' ? 'rtl' : 'ltr',
      m => { if (this.sessionId === sessionId) this.bridge.send({ v: 1, type: 'pageCounts', sessionId, ...m }) },
    )
    this.scheduleCount()
  }

  /** Counts again 400 ms after the last layout change. */
  private scheduleCount() {
    if (this.countTimer) clearTimeout(this.countTimer)
    const gen = ++this.countGen
    this.counter?.announce(gen)
    // 400 ms: after the new layout has been painted (a batch blocks the page for ~0.2 s, P-020), and a slider drag
    // counts once.
    this.countTimer = setTimeout(() => {
      this.countTimer = null
      void this.counter?.start(gen).catch(e => console.warn('count failed', e))
    }, 400)
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

  /** Only the open view's events count; `painted` is dropped if another open (or a close) came in between. */
  private onRelocate(view: any, sessionId: string, d: any) {
    if (this.view !== view || this.sessionId !== sessionId || this.seeking) return
    // A neighbour swapped in before its deferred restyle ran gets the current settings now.
    const shown = view.renderer?.getContents?.()[0]?.doc
    if (shown && applied.get(shown) !== this.settings) applySettings(shown, this.settings)
    const token = this.token
    this.bridge.send({
      // foliate reports NaN while a section has no layout yet; JSON turns NaN into null, which Kotlin rejects.
      v: 1, type: 'relocate', sessionId, cfi: d.cfi ?? '', fraction: Number.isFinite(d.fraction) ? d.fraction : 0,
      sectionIndex: d.section?.current ?? 0, tocHref: d.tocItem?.href ?? null, ...pageOf(view.renderer),
    })
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (this.token !== token) return
      this.bridge.send({ v: 1, type: 'painted', sessionId })
      if (!this.counter) this.startCounter(sessionId) // first page on screen: counting may start
    }))
  }

  /**
   * A finished one-finger gesture in paginated flow, reported by the paginator (`host-swipe`, PATCHES.md): one recognised
   * swipe = one queued turn, so a swipe during a running turn or before the first layout settled is not lost (C-026).
   * Ignored while text is selected (the gesture was a selection drag).
   */
  private onSwipe(view: any, d: { dx: number; dy: number; dt: number } | null) {
    if (this.view !== view || !this.sessionId || !d) return
    const contents: any[] = view.renderer?.getContents?.() ?? []
    if (contents.some(c => { const sel = c.doc?.getSelection?.(); return sel && !sel.isCollapsed })) return
    const doc = contents[0]?.doc
    const vertical = !!doc?.defaultView?.getComputedStyle(doc.documentElement).writingMode?.startsWith('vertical')
    const t = swipeToTurn(d.dx, d.dy, d.dt, effectiveDir(this.settings.swipeDirection, this.book?.dir), vertical)
    if (t) this.turn(t)
  }

  /** Center-tap detection for the chrome (F-033); edge taps are page turns when tap zones are on (decided in Kotlin). */
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
    if (this.countTimer) { clearTimeout(this.countTimer); this.countTimer = null }
    this.counter?.stop(); this.counter = null
    this.queue = []
    this.seeking = false
    const view = this.view
    if (view) this.clearSearch(view)
    this.search = null
    if (view && !view.lastLocation) { destroyView(view) }
    if (this.book && this.sessionId && this.has) {
      const kept = view?.lastLocation ? view : null
      if (kept) kept.style.visibility = 'hidden'
      this.warm = { sessionId: this.sessionId, book: this.book, has: this.has, view: kept }
    } else if (view?.lastLocation) destroyView(view)
    this.view = null; this.book = null; this.has = null; this.sessionId = null
  }
}

const MAX_TURNS = 3
/**
 * Search hit highlight: opaque orange; Overlayer.highlight draws it at 0.3 opacity, which reads on all four reading
 * themes (user highlights get other colours).
 */
const SEARCH_COLOR = '#ff9800'

type Cmd = { kind: 'turn'; dir: 'next' | 'prev' } | { kind: 'goTo'; target: string; id?: number } | { kind: 'fraction'; fraction: number }
  | { kind: 'text'; index: number; offset: number; length: number; needle: string } | { kind: 'clearSearch' }

/** Which settings each section document has (so a stale one can be caught up). */
const applied = new WeakMap<Document, WebSettings>()
function applySettings(doc: Document, s: WebSettings) { applyStyles(doc, s); applied.set(doc, s) }

/** Position inside the visible section as the paginator sees it (bridge relocate fields). */
function pageOf(r: any): { page: number; pages: number; sectionFraction: number } {
  try {
    if (!r || r.scrolled) {
      const f = r && r.viewSize > 0 ? r.start / r.viewSize : 0
      return { page: 0, pages: 0, sectionFraction: Number.isFinite(f) ? Math.min(1, Math.max(0, f)) : 0 }
    }
    const pages = Math.max(0, (r.pages ?? 0) - 2)
    const page = Math.min(pages, Math.max(pages ? 1 : 0, r.page ?? 0))
    return { page, pages, sectionFraction: pages > 1 ? (page - 1) / (pages - 1) : 0 }
  } catch { return { page: 0, pages: 0, sectionFraction: 0 } }
}

const IS_CFI = /^epubcfi\(.*\)$/

/**
 * Navigates to an href or a CFI. A CFI goes through [resolveSaved] (C-009 nearest valid position). Returns false if
 * the target's section does not resolve (nothing moved).
 */
async function goToTarget(view: any, target: string, initial = false): Promise<boolean> {
  if (!IS_CFI.test(target)) { await (initial ? view.init({ lastLocation: target }) : view.goTo(target)); return true }
  const resolved = resolveSaved(view.book, target)
  if (!resolved) return false
  await view.renderer.goTo(resolved)
  if (initial) view.history?.pushState?.(target)
  return true
}

function destroyView(view: any) {
  try { view.close?.() } catch { /* already broken */ }
  view.remove()
}
