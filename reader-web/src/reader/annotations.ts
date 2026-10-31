/**
 * F-060/F-061 user annotations as drawn on the page. The app owns them (database, original CFIs) and sends the book's
 * set (`setAnnotations`, virtual CFIs) plus changes (`addAnnotation`, `removeAnnotation`); this side keeps them per
 * section and draws them with foliate's overlayer whenever a section's overlayer exists.
 */

/**
 * The six highlight colours (ids as in core/data HighlightColors; the same values as the app's swatches in
 * ui/.../reader/selection/HighlightColorRow.kt HighlightPalette). Overlayer.highlight draws them at 0.3 opacity (0.4 on
 * the dark themes, reader.ts). The search hit is an outline, so the user's orange never reads as a search hit.
 */
export const HIGHLIGHT_COLORS: Readonly<Record<string, string>> = {
  yellow: '#fdd835',
  green: '#66bb6a',
  blue: '#42a5f5',
  pink: '#f06292',
  orange: '#ffa726',
  purple: '#ab47bc',
}

export type AnnotationStyle = 'highlight' | 'underline'

/** As the app sends it: [cfi] a virtual range CFI, [color] a HIGHLIGHT_COLORS id. */
export type AnnotationItem = { id: string; cfi: string; color: string; style: AnnotationStyle }

/** [index]: its section (-1 until known); [range]: the range foliate last drew it on (hit tests). */
export type DrawnAnnotation = AnnotationItem & { index: number; range: Range | null }

/** What the view must do after a change: draw (or redraw) these, erase these overlay keys (CFIs). */
export type AnnotationChange = { draw: DrawnAnnotation[]; erase: string[] }

type DrawFn = (rects: DOMRectList | DOMRect[], options?: Record<string, unknown>) => SVGElement
const tagged = new WeakMap<DrawFn, Map<string, DrawFn>>()

/**
 * [fn] wrapped so its drawing carries `data-er-id` = [id] (test probes find an annotation's drawing; foliate's
 * overlayer calls the same function again on every redraw). One wrapper per function and id.
 */
export function taggedDraw(fn: DrawFn, id: string): DrawFn {
  let byId = tagged.get(fn)
  if (!byId) tagged.set(fn, byId = new Map())
  let f = byId.get(id)
  if (!f) {
    f = (rects, options) => { const el = fn(rects, options); el.setAttribute('data-er-id', id); return el }
    if (byId.size > 5000) byId.clear() // bounded; wrappers are cheap to make again
    byId.set(id, f)
  }
  return f
}

/** Which overlayer function draws an annotation and in which CSS colour (unknown colour ids draw yellow). */
export function annotationDraw(style: AnnotationStyle, color: string): { kind: AnnotationStyle; color: string } {
  return { kind: style, color: HIGHLIGHT_COLORS[color] ?? HIGHLIGHT_COLORS.yellow }
}

/**
 * The session's annotations by id, by section and by overlay key. foliate keys an overlay by its CFI, so two
 * annotations on the identical range share one drawing: the later one is drawn; removing it draws the other again.
 * [indexOf] resolves a CFI's section without loading anything (-1 if it cannot; such an annotation is offered to every
 * new overlayer until foliate reports where it is, [located]).
 */
export class AnnotationStore {
  private byId = new Map<string, DrawnAnnotation>()
  private sections = new Map<number, Set<string>>()

  constructor(private readonly indexOf: (cfi: string) => number) {}

  /** The annotation drawn under overlay key [cfi] (the latest one with that range). */
  byValue(cfi: string): DrawnAnnotation | undefined {
    let found: DrawnAnnotation | undefined
    for (const a of this.byId.values()) if (a.cfi === cfi) found = a
    return found
  }

  /** The ones to draw when section [index] gets an overlayer (in insertion order), with the unlocated ones. */
  inSection(index: number): DrawnAnnotation[] {
    const ids = this.sections.get(index)
    const out: DrawnAnnotation[] = []
    for (const a of this.byId.values()) if (a.index < 0 || ids?.has(a.id)) out.push(a)
    return out
  }

  /** Replaces the whole set: unchanged items cost nothing; new or changed ones are drawn, vanished keys erased. */
  replace(items: readonly AnnotationItem[]): AnnotationChange {
    const next = new Map(items.map(i => [i.id, i]))
    const erase = new Set<string>()
    for (const [id, a] of this.byId) {
      const n = next.get(id)
      if (!n || n.cfi !== a.cfi) { this.drop(a); erase.add(a.cfi) }
    }
    const draw: DrawnAnnotation[] = []
    for (const i of next.values()) {
      const a = this.byId.get(i.id)
      if (a && a.color === i.color && a.style === i.style) continue
      draw.push(this.put(i, a))
    }
    for (const a of draw) erase.delete(a.cfi)
    // A key whose newest owner left but another annotation still has that range: draw that one instead.
    for (const cfi of [...erase]) { const other = this.byValue(cfi); if (other) { erase.delete(cfi); draw.push(other) } }
    return { draw, erase: [...erase] }
  }

  /** Adds or changes one; a new range for the same id erases the old drawing first. */
  upsert(i: AnnotationItem): AnnotationChange {
    const a = this.byId.get(i.id)
    const erase: string[] = []
    if (a && a.cfi !== i.cfi) {
      this.drop(a)
      const other = this.byValue(a.cfi)
      const draw = [this.put(i, undefined)]
      if (other) draw.unshift(other); else erase.push(a.cfi)
      return { draw, erase }
    }
    return { draw: [this.put(i, a)], erase }
  }

  remove(id: string): AnnotationChange {
    const a = this.byId.get(id)
    if (!a) return { draw: [], erase: [] }
    this.drop(a)
    const other = this.byValue(a.cfi)
    return other ? { draw: [other], erase: [] } : { draw: [], erase: [a.cfi] }
  }

  /** foliate resolved [cfi] into section [index]. */
  located(cfi: string, index: number) {
    for (const a of this.byId.values()) if (a.cfi === cfi && a.index < 0 && index >= 0) { a.index = index; this.bucket(a) }
  }

  /** foliate drew key [cfi] on [range] (kept for hit tests; foliate's overlayer redraws it on every re-layout). */
  drawn(cfi: string, range: Range | null) {
    const a = this.byValue(cfi)
    if (a) a.range = range
  }

  /**
   * The annotation of section [index] drawn under point ([x], [y]) of [doc] (its viewport coordinates), the latest
   * one first (drawn on top), or null. Reads the ranges' client rects: O(annotations in the section) per tap.
   */
  hit(index: number, doc: Document, x: number, y: number): DrawnAnnotation | null {
    const list = this.inSection(index)
    for (let i = list.length - 1; i >= 0; i--) {
      const a = list[i]
      const r = a.range
      if (!r || r.startContainer?.ownerDocument !== doc && r.startContainer !== doc) continue
      if (this.byValue(a.cfi) !== a) continue // hidden under a later one on the same range
      try {
        for (const { left, top, right, bottom } of Array.from(r.getClientRects())) {
          if (top <= y && left <= x && bottom > y && right > x) return a
        }
      } catch { /* a range in a document being unloaded */ }
    }
    return null
  }

  clear() { this.byId.clear(); this.sections.clear() }

  private put(i: AnnotationItem, old: DrawnAnnotation | undefined): DrawnAnnotation {
    let index = old?.cfi === i.cfi ? old.index : -1
    if (index < 0) { try { index = this.indexOf(i.cfi) } catch { index = -1 } }
    if (!(index >= 0)) index = -1
    const a: DrawnAnnotation = { ...i, index, range: old?.cfi === i.cfi ? old.range : null }
    if (old) this.drop(old)
    this.byId.set(a.id, a)
    this.bucket(a)
    return a
  }

  private bucket(a: DrawnAnnotation) {
    if (a.index < 0) return
    let s = this.sections.get(a.index)
    if (!s) this.sections.set(a.index, s = new Set())
    s.add(a.id)
  }

  private drop(a: DrawnAnnotation) {
    this.byId.delete(a.id)
    this.sections.get(a.index)?.delete(a.id)
  }
}
