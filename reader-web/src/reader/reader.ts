import '../../vendor/foliate-js/view.js'
import { EPUB } from '../../vendor/foliate-js/epub.js'
import { Overlayer } from '../../vendor/foliate-js/overlayer.js'
import type { Bridge } from '../bridge/bridge'
import type { TocItem } from '../bridge/protocol'
import { makeLoader, packageProblem } from './loader'

import { flattenToc } from './toc'
import { resolveSaved } from './position'
import { PageCounter } from './counter'
import { decoded, pageImages } from './paint'
import { polyfillMfenced } from './mathml'
import { shareSections } from './sections'
import { rangeAtOffset, resolveTextRange, textOffsetOf, textSpanOf } from './textpos'
import { dragFinishes, effectiveDir, followsFinger, reportSwipes, swipeToTurn } from './swipe'
import { externalHref, isNoteTarget, noteElement, noteLinkResolver, noteRef, sanitizeNote } from './links'
import { FootnotePopup } from './footnote'
import { visibleBookmarks } from './bookmarks'
import { readSelection, SelectionReporter } from './selection'
import { AnnotationStore, annotationDraw, taggedDraw, type AnnotationChange, type AnnotationItem } from './annotations'
import { applySettings as applyStyles, DEFAULT_SETTINGS, readerCss, THEMES, type WebSettings } from './styles'
import { chapterEdgeLabel, chapterSpan, DEFAULT_CHAPTER_TEXTS, type ChapterTexts } from './chapters'

/** The book identifier the app records on open (with the cd digest it is the book's identity, M2). */
const identifierOf = (book: any): string | null => book.metadata?.identifier || null

/** C-048: the error messages for package problems (the app shows its own wording by kind; these are for logs). */
const PROBLEM_TEXT = { no_package: 'No package found in this file.', nothing_readable: 'The book has no readable sections.' } as const

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
  /** F-041: the book's bookmarks (virtual CFIs) as last set by the app; empty at every open and close. */
  private bookmarks: string[] = []
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
  /** C-085: the app's texts for the One at a time chapter controls (from `open`). */
  private chapterTexts: ChapterTexts = DEFAULT_CHAPTER_TEXTS
  /** C-085 with M8: virtual sections that continue the one before (parts of a huge document): one chapter with it. */
  private continued: ReadonlySet<number> = new Set()
  private readonly css = readerCss(new URL('./', location.href).href)
  /** Page counts for the open book (F-034); one generation per layout. */
  private counter: PageCounter | null = null
  /** The session whose first page has gone through [onRelocate]. */
  private firstPaint: string | null = null
  private countGen = 0
  private countTimer: ReturnType<typeof setTimeout> | null = null
  /** The one temporary search highlight (F-050): its CFI and section, drawn again whenever that section's overlayer is made. */
  private search: { cfi: string; index: number } | null = null
  /**
   * F-042 followed-links history: the exact location (CFI) where each followed link was tapped, newest last. Only
   * links push; TOC, search and slider jumps do not (Back after them leaves the book). foliate's own view.history is
   * not used: it also records every goTo and goToFraction. Reset per open; the app mirrors its depth (`history`).
   */
  private links: string[] = []
  /** F-043 note popup in the host page. */
  private readonly popup = new FootnotePopup(document, href => this.onPopupLink(href), () => this.sendFootnote(false))
  /** F-060: the settled text selection, reported to the app (its selection menu acts on it). */
  private readonly selection = new SelectionReporter(
    () => readSelection(this.view?.renderer?.getContents?.() ?? [], (i, r) => this.view.getCFI(i, r)),
    s => {
      const sessionId = this.sessionId
      if (!sessionId) return
      this.bridge.send(s ? { v: 1, type: 'selection', sessionId, ...s } : { v: 1, type: 'selection', sessionId, text: '', cfi: null, sectionIndex: -1 })
    },
  )
  /**
   * F-060/F-061: the book's user annotations as the app sent them this session (`setAnnotations` + changes), per
   * section; drawn by the view's overlayer when their section has one (loaded now, or its `create-overlay` later).
   * foliate's overlayer keeps each drawn range and redraws it on every re-layout, so it stays on the same words.
   */
  private readonly annotations = new AnnotationStore(cfi => {
    const i = this.book?.resolveCFI?.(cfi)?.index
    return typeof i === 'number' ? i : -1
  })

  constructor(private bridge: Bridge) {
    // Read-only probes for the app's instrumented tests (evaluateJavascript); nothing in the app calls them.
    const identities = new Map<string, { identifier?: string | null; error?: string }>()
    ;(window as any).__readerTest = {
      /** C-094: the last drag (what it moved, how far, how it ended) and the page's offset now; null before any. */
      drag: () => { const r = this.view?.renderer; return r?.debugDrag ? { ...r.debugDrag, start: r.start, sliding: !!r.debugSlideGeometry } : null },
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
      /** Scroll and touch trace of the renderer (paginator.js startTrace), for a test's failure message. */
      traceStart: () => this.view?.renderer?.startTrace?.(),
      traceStop: () => JSON.stringify(this.view?.renderer?.stopTrace?.() ?? []),
      /** What is really at the top of the screen: a hit test down the viewport, whatever the reader thinks is current. */
      screenTop: () => {
        const x = window.innerWidth / 2
        for (let y = 8; y < 300; y += 8) for (const c of this.view?.renderer?.getContents?.() ?? []) {
          const box = (c.doc?.defaultView?.frameElement as HTMLElement | null)?.getBoundingClientRect()
          if (!box || y < box.top || y >= box.bottom) continue
          const el = c.doc.elementFromPoint(x - box.left, y - box.top)
          const text = el && el !== c.doc.body && el !== c.doc.documentElement ? el.textContent?.trim().slice(0, 60) : ''
          if (text) return { index: c.index, text }
        }
        return null
      },
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
      /**
       * C-085: One at a time's chapter controls: `{single, head, foot, scrollTop, scrollHeight, viewport, current}`; head
       * and foot are `{label, index, x, y, w, h}` (host viewport CSS px) or null when not shown; with dpr/vw/vh.
       */
      edges: () => { const e = this.view?.renderer?.debugEdges; return e ? { ...e, dpr: window.devicePixelRatio, vw: window.innerWidth, vh: window.innerHeight } : null },
      /** C-071: frames of the last boundary slide (`{ t, old, next }`, CSS px), [] if none ran. */
      slide: () => this.view?.renderer?.debugSlide ?? null,
      /** C-071: the slide's on-screen geometry now (container, both pages' iframes, old page attached), null if none runs. */
      slideGeometry: () => this.view?.renderer?.debugSlideGeometry ?? null,
      linear: () => (this.book?.sections ?? []).map((x: any, i: number) => (x.linear !== 'no' ? i : -1)).filter((i: number) => i >= 0),
      /**
       * Loads a registered session's package like an open does and keeps the identifier `opened` would report, without
       * a view or layout (IdentityAgreementTest over the whole corpus, C-049). Read it with identity(sessionId).
       */
      identify: (sessionId: string) => {
        identities.delete(sessionId)
        void makeLoader(`/book/${encodeURIComponent(sessionId)}`, '')
          .then(loader => new EPUB(loader as any).init())
          .then((book: any) => identities.set(sessionId, { identifier: identifierOf(book) }))
          .catch((e: unknown) => identities.set(sessionId, { error: String((e as Error)?.message ?? e) }))
      },
      identity: (sessionId: string) => identities.get(sessionId) ?? null,
      /**
       * C-072: typography and line breaks of the first paragraph (≥ [minChars] characters) of loaded section [index], to
       * compare paged and scrolled flow: computed font and spacing, the text column (paragraph box width and left edge
       * in the host viewport), transforms/zoom on the root and the iframe, one word's rendered width (text autosizing
       * would change it) and the first word of every line.
       */
      typography: (index: number, minChars = 400) => {
        const c = this.view?.renderer?.getContents?.().find((x: any) => x.index === index)
        const doc: Document | undefined = c?.doc
        const p = [...(doc?.querySelectorAll('p') ?? [])].find(e => (e.textContent ?? '').trim().length >= minChars)
        if (!doc || !p) return null
        const win = doc.defaultView!
        const cs = win.getComputedStyle(p)
        const frame = win.frameElement as HTMLElement | null
        const fr = frame?.getBoundingClientRect()
        // one rect per column fragment in paged flow: the one on screen (host coordinates) is the text column
        const frags = [...p.getClientRects()].map(r => ({ left: (fr?.left ?? 0) + r.left, width: r.width }))
        const pr = frags.find(r => r.left >= 0 && r.left < window.innerWidth) ?? frags[0] ?? { left: 0, width: 0 }
        const range = doc.createRange()
        const walker = doc.createTreeWalker(p, NodeFilter.SHOW_TEXT)
        const lines: string[] = []
        let lastTop = Number.NaN
        let wordWidth = 0
        for (let n = walker.nextNode(); n; n = walker.nextNode()) {
          const text = n.nodeValue ?? ''
          for (const m of text.matchAll(/\S+/g)) {
            range.setStart(n, m.index!); range.setEnd(n, m.index! + m[0].length)
            const r = range.getClientRects()[0]
            if (!r) continue
            if (!wordWidth) wordWidth = r.width
            if (Number.isNaN(lastTop) || Math.abs(r.top - lastTop) > r.height / 2) { lines.push(m[0]); lastTop = r.top }
          }
        }
        const root = win.getComputedStyle(doc.documentElement)
        return {
          fontFamily: cs.fontFamily, fontSize: cs.fontSize, fontWeight: cs.fontWeight, letterSpacing: cs.letterSpacing,
          wordSpacing: cs.wordSpacing, lineHeight: cs.lineHeight, textAlign: cs.textAlign,
          columnWidth: pr.width, columnLeft: pr.left, viewportWidth: window.innerWidth,
          rootTransform: root.transform, rootZoom: (root as any).zoom ?? '', frameTransform: frame ? getComputedStyle(frame).transform : '',
          frameZoom: frame ? (getComputedStyle(frame) as any).zoom ?? '' : '', textSizeAdjust: (cs as any).webkitTextSizeAdjust ?? '',
          rootPadding: `${root.paddingLeft} ${root.paddingRight}`, wordWidth, lines,
        }
      },
      /**
       * C-081: the first SVG `<image>` of loaded section [index]: its href (rewritten or not), whether it decodes, and its
       * box and its SVG's box in host px. Asynchronous (the decode): the result is left in `window.__svgImage`.
       */
      svgImage: (index: number) => {
        const w = window as any
        w.__svgImage = null
        const doc: Document | undefined = this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.doc
        const img = doc?.getElementsByTagNameNS('http://www.w3.org/2000/svg', 'image')[0] as SVGImageElement | undefined
        if (!doc || !img) { w.__svgImage = { none: true }; return }
        const f = (doc.defaultView?.frameElement as HTMLElement | null)?.getBoundingClientRect()
        const box = (r: DOMRect) => ({ left: (f?.left ?? 0) + r.left, top: (f?.top ?? 0) + r.top, width: r.width, height: r.height })
        const href = img.getAttribute('href') ?? img.getAttributeNS('http://www.w3.org/1999/xlink', 'href') ?? ''
        const probe = new Image()
        probe.onload = () => {
          w.__svgImage = { href, natural: probe.naturalWidth, img: box(img.getBoundingClientRect()),
            svg: box(img.ownerSVGElement!.getBoundingClientRect()), vw: window.innerWidth, vh: window.innerHeight }
        }
        probe.onerror = () => { w.__svgImage = { href, natural: 0 } }
        probe.src = href
      },
      /** C-082: left edge (host px) of the first [selector] in loaded section [index], on screen if it is split; null if none. */
      blockLeft: (index: number, selector: string) => {
        const doc: Document | undefined = this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.doc
        const el = doc?.querySelector(selector)
        if (!doc || !el) return null
        const f = (doc.defaultView?.frameElement as HTMLElement | null)?.getBoundingClientRect()
        const lefts = [...el.getClientRects()].map(r => (f?.left ?? 0) + r.left)
        return lefts.find(l => l >= 0 && l < window.innerWidth) ?? lefts[0] ?? null
      },
      /** C-088: left and right edges (host px) of the first [selector] in loaded section [index], its box on screen; null if none. */
      blockBox: (index: number, selector: string) => {
        const doc: Document | undefined = this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.doc
        const el = doc?.querySelector(selector)
        if (!doc || !el) return null
        const f = (doc.defaultView?.frameElement as HTMLElement | null)?.getBoundingClientRect()
        const rects = [...el.getClientRects()].map(r => ({ left: (f?.left ?? 0) + r.left, right: (f?.left ?? 0) + r.right }))
        return rects.find(r => r.left >= 0 && r.left < window.innerWidth) ?? rects[0] ?? null
      },
      goIndex: (i: number) => { void this.view?.renderer?.goTo({ index: i, anchor: 0 }) },
      htmlVar: (index: number, name: string) => this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.doc?.documentElement.style.getPropertyValue(name) ?? null,
      /** F-043: text of the open footnote popup, or null. */
      footnote: () => this.popup.text(),
      /** F-042: entries in the followed-links history. */
      historyDepth: () => this.links.length,
      /**
       * Host-viewport rectangle (CSS px) of the first link in the shown sections whose text is [text], with the
       * device pixel ratio, so a test can tap it for real; null if there is none.
       */
      linkRect: (text: string) => {
        for (const c of this.view?.renderer?.getContents?.() ?? []) {
          const a = [...(c.doc?.querySelectorAll('a[href]') ?? [])].find((x: any) => x.textContent?.trim() === text) as Element | undefined
          if (!a) continue
          const box = (c.doc.defaultView?.frameElement as HTMLElement | null)?.getBoundingClientRect()
          const r = a.getClientRects()[0] ?? a.getBoundingClientRect()
          return { x: (box?.left ?? 0) + r.left, y: (box?.top ?? 0) + r.top, w: r.width, h: r.height, dpr: window.devicePixelRatio, vw: window.innerWidth, vh: window.innerHeight }
        }
        return null
      },
      /** Clicks the first link whose text is [text] in the shown sections (like a tap; foliate's link handling runs). */
      clickLink: (text: string) => {
        for (const c of this.view?.renderer?.getContents?.() ?? []) {
          const a = [...(c.doc?.querySelectorAll('a[href]') ?? [])].find((x: any) => x.textContent?.trim() === text) as HTMLElement | undefined
          if (a) { a.click(); return true }
        }
        return false
      },
      /**
       * F-060: host-viewport rectangle (CSS px, with dpr/vw/vh like linkRect) of the first whole-word occurrence of
       * [word] that is fully on screen in the shown sections, or null; a test long-presses it for real.
       */
      wordRect: (word: string) => {
        const hit = this.findWord(word)
        if (!hit) return null
        return { x: hit.x, y: hit.y, w: hit.w, h: hit.h, index: hit.index, dpr: window.devicePixelRatio, vw: window.innerWidth, vh: window.innerHeight }
      },
      /** F-060: selects the first on-screen occurrence of [word] programmatically (as a long-press would); false if none. */
      selectWord: (word: string) => {
        const hit = this.findWord(word)
        if (!hit) return false
        const sel = hit.range.startContainer.ownerDocument!.getSelection()!
        sel.removeAllRanges(); sel.addRange(hit.range)
        return true
      },
      /** M8: text of the range [cfi] (the reader's own, virtual coordinates) in its loaded section, or null. */
      cfiText: (cfi: string) => this.cfiRange(cfi)?.toString() ?? null,
      /** M8: selects the range [cfi] in its loaded section (as a long-press would); false if it is not loaded. */
      selectCfi: (cfi: string) => {
        const r = this.cfiRange(cfi)
        if (!r) return false
        const sel = r.startContainer.ownerDocument!.getSelection()!
        sel.removeAllRanges(); sel.addRange(r)
        return true
      },
      /** F-060: `Selection.toString()` of the section document holding a selection, or null. */
      selectionText: () => {
        for (const c of this.view?.renderer?.getContents?.() ?? []) {
          const sel = c.doc?.getSelection?.()
          if (sel && !sel.isCollapsed) return sel.toString()
        }
        return null
      },
      /**
       * F-061: annotation [id] as drawn: the text of its range, the range's union rectangle and the union of its overlay
       * drawing (host-viewport CSS px, with dpr), or null when it is not drawn in any loaded section.
       */
      annotation: (id: string) => {
        for (const c of this.view?.renderer?.getContents?.() ?? []) {
          const el = c.overlayer?.element?.querySelector?.(`[data-er-id="${CSS.escape(id)}"]`) as SVGGraphicsElement | null
          if (!el) continue
          const a = this.annotations.inSection(c.index).find(x => x.id === id)
          const box = (c.doc?.defaultView?.frameElement as HTMLElement | null)?.getBoundingClientRect()
          const union = (rs: Iterable<DOMRect>, dx: number, dy: number) => {
            let l = Infinity, t = Infinity, r = -Infinity, b = -Infinity
            for (const x of rs) { if (!x.width && !x.height) continue; l = Math.min(l, x.left); t = Math.min(t, x.top); r = Math.max(r, x.right); b = Math.max(b, x.bottom) }
            return l === Infinity ? null : { x: l + dx, y: t + dy, w: r - l, h: b - t }
          }
          return {
            index: c.index, text: a?.range?.toString() ?? null,
            range: a?.range ? union(Array.from(a.range.getClientRects()), box?.left ?? 0, box?.top ?? 0) : null,
            overlay: union([...el.querySelectorAll('rect, path')].map(x => x.getBoundingClientRect()), 0, 0),
            dpr: window.devicePixelRatio, vw: window.innerWidth, vh: window.innerHeight,
          }
        }
        return null
      },
      overlayCount: (index: number) => this.view?.renderer?.getContents?.().find((x: any) => x.index === index)?.overlayer?.element?.childElementCount ?? -1,
    }
    // A new viewport (rotation, window size) is a new layout: count again (F-034, P-022).
    window.addEventListener('resize', () => { if (this.view) this.scheduleCount() })
    bridge.onMessage(m => {
      switch (m.type) {
        case 'open':
          if (m.settings) this.settings = m.settings
          this.chapterTexts = {
            nextChapter: m.texts.nextChapter ?? DEFAULT_CHAPTER_TEXTS.nextChapter,
            previousChapter: m.texts.previousChapter ?? DEFAULT_CHAPTER_TEXTS.previousChapter,
            nextChapterUntitled: m.texts.nextChapterUntitled ?? DEFAULT_CHAPTER_TEXTS.nextChapterUntitled,
            previousChapterUntitled: m.texts.previousChapterUntitled ?? DEFAULT_CHAPTER_TEXTS.previousChapterUntitled,
          }
          this.continued = new Set(m.continued ?? [])
          void this.open(m.sessionId, m.position, m.texts.missingPart); break
        case 'setSettings': this.setSettings(m.settings); break
        case 'goToFraction': this.enqueue({ kind: 'fraction', fraction: m.fraction, id: m.id }); break
        case 'close': this.close(); break
        case 'next': this.turn('next'); break
        case 'prev': this.turn('prev'); break
        case 'goTo': this.enqueue({ kind: 'goTo', target: m.target, id: m.id }); break
        case 'goToText': this.enqueue({ kind: 'text', index: m.sectionIndex, offset: m.offset, length: m.length, needle: m.needle }); break
        case 'clearSearchHighlight': this.enqueue({ kind: 'clearSearch' }); break
        case 'back': this.back(); break
        case 'closeFootnote': if (this.popup.close()) this.sendFootnote(false); break
        case 'setBookmarks': this.setBookmarks(m.sessionId, m.cfis); break
        case 'addAnnotation': this.changeAnnotations(m.sessionId, s => s.upsert({ id: m.id, cfi: m.cfi, color: m.color, style: m.style })); break
        case 'setAnnotations': this.changeAnnotations(m.sessionId, s => s.replace(m.items)); break
        case 'removeAnnotation': this.changeAnnotations(m.sessionId, s => s.remove(m.id)); break
        case 'clearSelection': this.clearSelection(); break
        case 'goToOffset': this.enqueue({ kind: 'offset', index: m.sectionIndex, offset: m.offset }); break
        case 'textOffsets': void this.textOffsets(m.sessionId, m.id, m.cfis); break
      }
    })
  }

  private async open(sessionId: string, position: string | null, missingPart: string) {
    this.close()
    this.sessionId = sessionId
    this.bookmarks = []
    this.annotations.clear()
    this.selection.reset()
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
        let book: any
        try {
          book = await new EPUB(loader as any).init()
        } catch (e) {
          const problem = await packageProblem(loader).catch(() => null)
          if (!problem) throw e
          if (!stale()) this.bridge.send({ v: 1, type: 'error', sessionId, kind: problem, message: PROBLEM_TEXT[problem] })
          return
        }
        shareSections((book.sections ?? []).filter(Boolean) as any[]) // the paginator and the page counter share each section's resources
        if (stale()) return
        this.book = book
        this.has = loader.has
      }
      if (stale()) return
      const book = this.book
      if (!book.sections?.length) {
        this.book = null; this.has = null
        this.bridge.send({ v: 1, type: 'error', sessionId, kind: 'nothing_readable', message: PROBLEM_TEXT.nothing_readable })
        return
      }
      this.sendOpened(sessionId)
      const view = document.createElement('foliate-view') as any
      this.view = view
      document.body.append(view)
      view.addEventListener('relocate', (e: CustomEvent) => this.onRelocate(view, sessionId, e.detail))
      view.addEventListener('load', (e: CustomEvent) => {
        polyfillMfenced(e.detail.doc); applySettings(e.detail.doc, this.settings); this.onLoad(sessionId, e.detail.doc)
        if (isFixedLayout(view.renderer)) reportSwipes(e.detail.doc, view.renderer) // F-032 on FXL pages
      })
      view.addEventListener('draw-annotation', (e: CustomEvent) => {
        const value = e.detail.annotation?.value
        if (value === this.search?.cfi) { e.detail.draw(Overlayer.outline, { color: SEARCH_COLOR, width: 2, radius: 3 }); return }
        const a = this.annotations.byValue(value)
        if (!a) return
        this.annotations.drawn(value, e.detail.range ?? null)
        const d = annotationDraw(a.style, a.color)
        if (d.kind === 'underline') e.detail.draw(taggedDraw(Overlayer.underline, a.id), { color: d.color, width: 2 })
        else e.detail.draw(taggedDraw(Overlayer.highlight, a.id), { color: d.color })
      })
      view.addEventListener('create-overlay', (e: CustomEvent) => {
        if (this.search && e.detail.index === this.search.index) this.drawSearch(view)
        for (const a of this.annotations.inSection(e.detail.index)) this.drawAnnotation(view, a)
      })
      // foliate's link handling (view.js #handleLinks) finds the link and emits these; the host decides what happens.
      view.addEventListener('link', (e: CustomEvent) => { e.preventDefault(); void this.onLink(view, sessionId, e.detail?.a, e.detail?.href) })
      view.addEventListener('external-link', (e: CustomEvent) => { e.preventDefault(); this.onExternal(sessionId, e.detail?.href_) })
      await view.open(book)
      if (stale()) return
      const r = view.renderer
      // C-085: the One at a time controls name the chapter they open (its contents entry)
      let toc: TocItem[] | null = null
      const has = this.has!
      r.edgeLabel = (index: number, dir: 1 | -1) => chapterEdgeLabel(toc ??= flattenToc(book, has), index, dir, this.chapterTexts)
      const continued = this.continued
      r.chapterSpan = (index: number) => chapterSpan(continued, book.sections.length, index)
      this.layoutAttributes(r)
      // Swipes come to the host as `swipe` events and turn pages through the queue (F-032, C-026), not foliate's drag.
      r.setAttribute('host-swipe', '')
      r.addEventListener('swipe', (e: CustomEvent) => this.onSwipe(view, e.detail))
      // C-094: the page follows a drag only with nothing else on its way; a gesture it can't follow comes as a `swipe`.
      r.dragHost = {
        ready: (rtl: boolean, vertical: boolean) => !this.pumping && !this.queue.length && !this.seeking
          && followsFinger(effectiveDir(this.settings.swipeDirection, this.book?.dir), rtl, vertical),
        finishes: dragFinishes,
      }
      if (isFixedLayout(r)) reportSwipes(r, r) // foliate-fxl has no touch handling: the host recognises its swipes
      // F-081: vertical-rl sections are paged right-to-left (horizontal pages), where the engine supports it.
      r.setAttribute('vertical-pages', '')
      r.setAttribute('max-column-count', '1')
      r.setStyles?.(this.css) // the fixed-layout renderer (F-082) has none: pre-paginated pages keep their own styles
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

  /**
   * At most [MAX_TURNS] pending; more are dropped (a burst of taps still moves that many pages, in order). A swipe is
   * never dropped (C-094): each is a whole gesture, so there are never many, and a section still loading can hold them up.
   */
  private turn(dir: 'next' | 'prev', chapter = false, swipe = false) {
    if (!swipe && this.queue.filter(c => c.kind === 'turn').length >= MAX_TURNS) return
    this.enqueue({ kind: 'turn', dir, chapter })
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
        // C-094: a finger holds the page, or its turn is settling: foliate would ignore a turn or a jump meanwhile
        const held = view.renderer?.dragging
        if (held) { await held; continue }
        const c = this.queue.shift()!
        if (c.kind === 'turn') {
          // Resolves after the turn (and foliate's lock) is over, so the next one is not ignored.
          try {
            if (c.chapter) await view.renderer.turnChapter(c.dir === 'next' ? 1 : -1)
            else await (c.dir === 'next' ? view.next() : view.prev())
          } catch (e) { console.warn('turn failed', e) }
          continue
        }
        if (c.kind === 'clearSearch') { this.clearSearch(view); continue }
        let hit: { cfi: string; index: number } | null = null
        this.seeking = true
        try {
          if (c.kind === 'fraction') await view.goToFraction(c.fraction)
          else if (c.kind === 'offset') {
            const cfi = await this.offsetCfi(view, c.index, c.offset)
            if (!cfi || !(await goToTarget(view, cfi))) await view.renderer.goTo({ index: c.index, anchor: 0 })
          } else if (c.kind === 'text') {
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
        if ((c.kind === 'goTo' || c.kind === 'fraction') && c.id != null) this.bridge.send({ v: 1, type: 'seeked', sessionId, id: c.id })
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

  /** C-083 go to page: the CFI of the first character at or after text [offset] of section [index], or null. */
  private async offsetCfi(view: any, index: number, offset: number): Promise<string | null> {
    try {
      const doc: Document | undefined = await this.book?.sections?.[index]?.createDocument?.()
      const range = doc && rangeAtOffset(doc, offset)
      return range ? view.getCFI(index, range) : null
    } catch (e) { console.warn('page start not resolved', e); return null }
  }

  /**
   * C-083: [section, text offset] of each CFI's start (the lists' book pages), from the section's loaded document or a
   * parsed copy (never laid out), [-1, -1] when it does not resolve. Answered once, in order; dropped if the book changed.
   */
  private async textOffsets(sessionId: string, id: number, cfis: string[]) {
    if (this.sessionId !== sessionId || !this.book) return
    const book = this.book
    const docs = new Map<number, Promise<Document | null>>()
    const docOf = (index: number): Promise<Document | null> => {
      let d = docs.get(index)
      if (!d) {
        const loaded = this.view?.renderer?.getContents?.().find((c: any) => c.index === index)?.doc as Document | undefined
        d = loaded ? Promise.resolve(loaded) : Promise.resolve(book.sections?.[index]?.createDocument?.()).then((x: any) => x ?? null, () => null)
        docs.set(index, d)
      }
      return d
    }
    const offsets: [number, number][] = []
    for (const cfi of cfis) {
      try {
        const t = book.resolveCFI(cfi)
        const doc = t && t.index >= 0 ? await docOf(t.index) : null
        const r = doc ? t.anchor(doc) : null
        if (r && typeof r === 'object' && 'startContainer' in r) { offsets.push([t.index, textOffsetOf(doc!, r.startContainer, r.startOffset)]); continue }
      } catch { /* does not resolve */ }
      offsets.push([-1, -1])
    }
    if (this.sessionId === sessionId && this.book === book) this.bridge.send({ v: 1, type: 'textOffsets', sessionId, id, offsets })
  }

  private drawSearch(view: any) {
    if (this.search) void view.addAnnotation({ value: this.search.cfi }).catch?.((e: unknown) => console.warn('search highlight failed', e))
  }

  private clearSearch(view: any) {
    const s = this.search
    this.search = null
    if (!s) return
    // foliate keys overlays by CFI: a user highlight on exactly the hit's range was drawn as the outline meanwhile and
    // goes with it, so it is drawn again (F-061).
    const own = this.annotations.byValue(s.cfi)
    void Promise.resolve(view.deleteAnnotation({ value: s.cfi })).catch(() => {}).then(() => { if (own && this.view === view) this.drawAnnotation(view, own) })
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

  /**
   * Side margins, the flow mode, and the vertical margins (page indicator room). F-072, C-082: m % is the real distance
   * from the screen edge to the text on each side. Foliate's text inset in paged flow is its half-gap grid column plus
   * half its column gap, g × width per side for a gap of g, so the gap attribute is m % (it was 2m %, twice the
   * setting); scrolled flow uses the same inset (C-072), and book CSS cannot add to it (styles.ts).
   */
  private layoutAttributes(r: any) {
    const s = this.settings
    const set = (k: string, v: string) => { if (r.getAttribute(k) !== v) r.setAttribute(k, v) }
    set('margin', '32px')
    set('gap', `${s.marginPercent}%`)
    // C-085: before `flow`, so switching to Scroll lays out the chosen chapter mode once (each change re-renders)
    if (s.chapters === 'one-at-a-time') set('chapter-stop', '')
    else r.removeAttribute('chapter-stop')
    set('flow', s.flow)
    if (s.pageAnimation === 'none') r.removeAttribute('animated') // F-032: no slide
    else set('animated', '')
    document.body.style.background = THEMES[s.theme]?.bg ?? ''
    r.style?.setProperty('--er-edge-fg', THEMES[s.theme]?.fg ?? '') // C-085: the chapter controls in the theme's colours
    r.style?.setProperty('--er-edge-rule', THEMES[s.theme]?.rule ?? '')
    // F-061: highlight fills read a little stronger on the dark themes (foliate's overlayer opacity variable).
    document.body.style.setProperty('--overlayer-highlight-opacity', THEMES[s.theme]?.dark ? '0.4' : '0.3')
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
    // A vertical-rl book paged right-to-left (`vertical-pages`) is counted one section at a time in a foliate View with
    // the paginator's layout (C-032). Other vertical books (vertical-lr, or an engine without column-wrap: foliate's
    // top-to-bottom pages) get their section list (so the page number can be shown from the text model, C-017) but no
    // book-wide total.
    const r = this.view?.renderer
    const doc = r?.getContents?.()[0]?.doc
    const mode = doc ? doc.defaultView?.getComputedStyle(doc.documentElement).writingMode ?? '' : ''
    const vertical = mode.startsWith('vertical')
    if (vertical && !(mode === 'vertical-rl' && r?.pagedLayout?.verticalPages)) {
      const linear = book.sections.map((x: any, i: number) => (x?.linear !== 'no' ? i : -1)).filter((i: number) => i >= 0)
      this.bridge.send({ v: 1, type: 'pageCounts', sessionId, gen: ++this.countGen, linear, counts: [], sample: false, done: false })
      return
    }
    this.counter = new PageCounter(
      book.sections, () => this.view?.renderer ?? null, () => this.settings,
      String(book.metadata?.language ?? ''), vertical ? '' : book.dir === 'rtl' ? 'rtl' : 'ltr',
      m => { if (this.sessionId === sessionId) this.bridge.send({ v: 1, type: 'pageCounts', sessionId, ...m }) },
      { alone: vertical },
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
      identifier: identifierOf(book),
      title: text(book.metadata?.title),
      authors: [book.metadata?.author ?? []].flat().map(text).filter(Boolean),
      dir: book.dir === 'rtl' ? 'rtl' : 'ltr',
      fixedLayout: book.rendition?.layout === 'pre-paginated',
      sections: book.sections.length,
      toc: flattenToc(book, this.has!),
      // C-073: foliate's SectionProgress sizes, so the app can tell where a slider seek will land before it is sent
      sizes: book.sections.map((s: any) => s.linear !== 'no' && s.size > 0 ? s.size : 0),
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
      ...onPage(this.bookmarks, d.cfi), ...spanOf(d.range),
    })
    const paint = () => requestAnimationFrame(() => requestAnimationFrame(() => {
      if (this.token !== token) return
      this.bridge.send({ v: 1, type: 'painted', sessionId })
      if (!this.counter) this.startCounter(sessionId) // first page on screen: counting may start
    }))
    const images = this.firstPaint === sessionId ? [] : pageImages(view.renderer?.getContents?.() ?? [], window.innerWidth, window.innerHeight)
    this.firstPaint = sessionId
    if (images.length) void decoded(images).then(paint) // C-089: the first page replaces the loading screen whole
    else paint()
  }

  /** F-041: remember the bookmarks and tell the app which are on the page now showing (the page itself did not change). */
  private setBookmarks(sessionId: string, cfis: string[]) {
    if (this.sessionId !== sessionId) return
    this.bookmarks = cfis
    const at = this.view?.lastLocation?.cfi
    this.bridge.send({ v: 1, type: 'pageBookmarks', sessionId, cfis: this.seeking || !at ? [] : visibleBookmarks(cfis, at) })
  }

  /** Range of [cfi] in its section's loaded document (test probes), or null. */
  private cfiRange(cfi: string): Range | null {
    try {
      const t = this.book?.resolveCFI?.(cfi)
      const doc = this.view?.renderer?.getContents?.().find((x: any) => x.index === t?.index)?.doc
      const r = doc && t?.anchor ? t.anchor(doc) : null
      return r && typeof r === 'object' && 'startContainer' in r ? (r as Range) : null
    } catch { return null }
  }

  /** Test probe helper: the first whole-word occurrence of [word] fully inside the host viewport. */
  private findWord(word: string): { range: Range; index: number; x: number; y: number; w: number; h: number } | null {
    const re = new RegExp(`(^|[^\\p{L}\\p{N}])(${word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})(?=$|[^\\p{L}\\p{N}])`, 'u')
    for (const c of this.view?.renderer?.getContents?.() ?? []) {
      const doc: Document | undefined = c.doc
      if (!doc?.body) continue
      const box = (doc.defaultView?.frameElement as HTMLElement | null)?.getBoundingClientRect()
      const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT)
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const m = re.exec(n.nodeValue ?? '')
        if (!m) continue
        const start = m.index + m[1].length
        const range = doc.createRange(); range.setStart(n, start); range.setEnd(n, start + word.length)
        const r = range.getClientRects()[0]
        if (!r) continue
        const x = (box?.left ?? 0) + r.left, y = (box?.top ?? 0) + r.top
        if (x >= 0 && y >= 0 && x + r.width <= window.innerWidth && y + r.height <= window.innerHeight) return { range, index: c.index, x, y, w: r.width, h: r.height }
      }
    }
    return null
  }

  /**
   * F-061: applies a change to the session's annotations: erased keys go first (no stale drawing is ever left), then
   * the new or changed ones are drawn, but only in sections that are loaded now; the rest are drawn when their section
   * gets its overlayer (`create-overlay`), so a book with hundreds of highlights costs nothing on open or on a turn.
   */
  private changeAnnotations(sessionId: string, change: (s: AnnotationStore) => AnnotationChange) {
    const view = this.view
    if (this.sessionId !== sessionId || !view) return
    const { draw, erase } = change(this.annotations)
    for (const cfi of erase) void Promise.resolve(view.deleteAnnotation({ value: cfi })).catch(() => {})
    if (!draw.length) return
    const loaded = new Set<number>((view.renderer?.getContents?.() ?? []).map((c: any) => c.index))
    for (const a of draw) if (a.index < 0 || loaded.has(a.index)) this.drawAnnotation(view, a)
  }

  private drawAnnotation(view: any, a: AnnotationItem) {
    Promise.resolve(view.addAnnotation({ value: a.cfi }))
      .then((r: { index?: number } | undefined) => { if (typeof r?.index === 'number') this.annotations.located(a.cfi, r.index) })
      .catch((e: unknown) => console.warn('annotation not drawn', e))
  }

  /** F-060: after a selection menu action the selection goes away (every loaded section). */
  private clearSelection() {
    for (const c of this.view?.renderer?.getContents?.() ?? []) {
      try { c.doc?.getSelection?.()?.removeAllRanges() } catch { /* a document being unloaded */ }
    }
    this.selection.changed()
  }

  /**
   * A finished one-finger gesture in paginated flow, reported by the paginator (`host-swipe`, PATCHES.md): one recognised
   * swipe = one queued turn, so a swipe during a running turn or before the first layout settled is not lost (C-026).
   * Ignored while text is selected (the gesture was a selection drag). In scrolled flow only One at a time reports
   * swipes, those clearly horizontal (C-093): the turn is to the next or previous chapter.
   */
  private onSwipe(view: any, d: { dx: number; dy: number; dt: number } | null) {
    if (this.view !== view || !this.sessionId || !d) return
    const contents: any[] = view.renderer?.getContents?.() ?? []
    if (contents.some(c => { const sel = c.doc?.getSelection?.(); return sel && !sel.isCollapsed })) return
    const doc = contents[0]?.doc
    const vertical = !!doc?.defaultView?.getComputedStyle(doc.documentElement).writingMode?.startsWith('vertical')
    const t = swipeToTurn(d.dx, d.dy, d.dt, effectiveDir(this.settings.swipeDirection, this.book?.dir), vertical)
    if (t) this.turn(t, view.renderer.getAttribute?.('flow') === 'scrolled', true)
  }

  /**
   * F-042/F-043: a tapped link inside the book. A note reference (marked, or a superscript link, or a link to an
   * element marked as a note in a section that is already loaded) whose note can be found opens the popup; anything
   * else is followed with a history entry. Ordinary links never wait for another section to load before moving.
   */
  private async onLink(view: any, sessionId: string, a: Element | undefined, href: string | undefined) {
    if (this.view !== view || this.sessionId !== sessionId || !href) return
    const book = this.book
    let target: { index: number; anchor?: (d: Document) => Element | Range | number | null } | null = null
    try { target = book?.resolveHref?.(href) ?? null } catch (e) { console.warn('link did not resolve', href, e) }
    if (!target || !(target.index >= 0)) { console.warn('link target is not in the book', href); return }
    const ref = a ? noteRef(a) : 'no'
    try {
      const loaded = (view.renderer?.getContents?.() ?? []).find((c: any) => c.index === target!.index)?.doc as Document | undefined
      const doc: Document | undefined = ref !== 'no' ? await book.sections?.[target.index]?.createDocument?.() : loaded
      if (this.view !== view || this.sessionId !== sessionId) return
      const found = doc && target.anchor ? target.anchor(doc) : null
      const el = typeof found === 'object' ? noteElement(found) : null
      if (el && el !== doc!.body && (ref !== 'no' || isNoteTarget(el))) {
        const section = book.sections[target.index]
        const content = sanitizeNote(el, document, noteLinkResolver(section))
        this.popup.show(content, this.settings)
        this.sendFootnote(true)
        return
      }
    } catch (e) { console.warn('note lookup failed', href, e) }
    this.follow(href)
  }

  /** Follows an in-book link: the current location goes on the history, then the jump is queued like a goTo. */
  private follow(href: string) {
    const cfi = this.view?.lastLocation?.cfi
    if (cfi) { this.links.push(cfi); this.sendHistory() }
    this.enqueue({ kind: 'goTo', target: href })
  }

  /** F-042 Back: to where the last followed link was tapped. With nothing to go back to, only the depth is confirmed. */
  private back() {
    if (!this.sessionId) return
    const cfi = this.links.pop()
    this.sendHistory()
    if (cfi) this.enqueue({ kind: 'goTo', target: cfi })
  }

  /** A link in the popup: external ones go to the app; internal ones close the popup and are followed. */
  private onPopupLink(href: string) {
    const sessionId = this.sessionId
    if (!sessionId) return
    if (/^(?!blob)\w+:/i.test(href)) { this.onExternal(sessionId, href); return }
    if (this.popup.close()) this.sendFootnote(false)
    this.follow(href)
  }

  /** F-044: only http(s) and mailto reach the app (it confirms and opens them); other schemes are dropped here. */
  private onExternal(sessionId: string, href: string | undefined) {
    if (this.sessionId !== sessionId || !href) return
    const url = externalHref(href)
    if (url) this.bridge.send({ v: 1, type: 'externalLink', sessionId, href: url })
    else console.warn('ignoring link with an unsupported scheme')
  }

  private sendHistory() {
    if (this.sessionId) this.bridge.send({ v: 1, type: 'history', sessionId: this.sessionId, depth: this.links.length })
  }

  private sendFootnote(open: boolean) {
    if (this.sessionId) this.bridge.send({ v: 1, type: 'footnote', sessionId: this.sessionId, open })
  }

  /**
   * Center-tap detection for the chrome (F-033); edge taps are page turns when tap zones are on (decided in Kotlin).
   * A tap that only dismisses a text selection (it was there when the finger went down; the browser collapses it before
   * the click) is not a tap for the app (F-060). Selection changes are reported to the app once settled.
   */
  private onLoad(sessionId: string, doc: Document) {
    let dismissing = false
    doc.addEventListener('pointerdown', () => { const sel = doc.getSelection?.(); dismissing = !!sel && !sel.isCollapsed })
    doc.addEventListener('selectionchange', () => { if (this.sessionId === sessionId) this.selection.changed() })
    doc.addEventListener('click', (e: MouseEvent) => {
      if (this.sessionId !== sessionId) return
      if (dismissing) { dismissing = false; return }
      if ((e.target as Element | null)?.closest?.('a[href]')) return // links win over annotations (foliate follows them)
      if (doc.getSelection && !doc.getSelection()!.isCollapsed) return
      // F-061: a tap on a highlight or underline opens it in the app; it neither toggles the controls nor turns a page.
      const index = (this.view?.renderer?.getContents?.() ?? []).find((c: any) => c.doc === doc)?.index
      const hit = typeof index === 'number' ? this.annotations.hit(index, doc, e.clientX, e.clientY) : null
      if (hit) { this.bridge.send({ v: 1, type: 'annotationTapped', sessionId, id: hit.id }); return }
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
    this.bookmarks = []
    this.annotations.clear()
    this.selection.reset()
    this.token++ // invalidates any open still in flight
    if (this.countTimer) { clearTimeout(this.countTimer); this.countTimer = null }
    this.counter?.stop(); this.counter = null
    this.queue = []
    this.seeking = false
    this.links = []
    this.popup.close()
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
 * Search hit: an orange outline (2 px, rounded), which reads on all four reading themes and never looks like a user
 * highlight (those are fills or underlines, the user's orange included).
 */
const SEARCH_COLOR = '#ff9800'

type Cmd = { kind: 'turn'; dir: 'next' | 'prev'; chapter?: boolean } | { kind: 'goTo'; target: string; id?: number } | { kind: 'fraction'; fraction: number; id?: number }
  | { kind: 'offset'; index: number; offset: number }
  | { kind: 'text'; index: number; offset: number; length: number; needle: string } | { kind: 'clearSearch' }

/** Which settings each section document has (so a stale one can be caught up). */
const applied = new WeakMap<Document, WebSettings>()
function applySettings(doc: Document, s: WebSettings) { applyStyles(doc, s); applied.set(doc, s) }

/** F-041: the `bookmarks` field of a relocate (left out when none is on the page). */
function onPage(bookmarks: readonly string[], cfi: string | undefined): { bookmarks?: string[] } {
  if (!bookmarks.length || !cfi) return {}
  const on = visibleBookmarks(bookmarks, cfi)
  return on.length ? { bookmarks: on } : {}
}

/** C-083: the visible range's text offsets (relocate `textOffset`/`textEnd`), left out when there is no range. */
function spanOf(range: Range | undefined): { textOffset?: number; textEnd?: number } {
  try { return (range && textSpanOf(range)) || {} } catch { return {} }
}

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

/** foliate's fixed-layout renderer (pre-paginated books, F-082): no setStyles, no swipes of its own. */
function isFixedLayout(r: any): boolean { return r?.localName === 'foliate-fxl' }
