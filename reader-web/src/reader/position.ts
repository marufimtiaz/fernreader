import * as CFI from '../../vendor/foliate-js/epubcfi.js'

type Step = { index: number; offset?: number | null }

/** The steps after the `!` of a CFI's start (the path inside the content document), or null if there are none. */
export function contentSteps(cfi: string): Step[] | null {
  try {
    const paths = (CFI as any).collapse((CFI as any).parse(cfi)) as Step[][]
    return paths.length > 1 ? paths[paths.length - 1] : null
  } catch { return null }
}

const nonBlankText = (root: Node, last: boolean): Text | null => {
  const doc = root.ownerDocument ?? (root as Document)
  const w = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: n => (n.nodeValue ?? '').trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP,
  })
  let found: Text | null = null
  while (w.nextNode()) { found = w.currentNode as Text; if (!last) break }
  return found
}

/** A one-character range at the start or the end of the visible text inside `node` (a collapsed range has no rects). */
const edgeRange = (doc: Document, node: Node, atEnd: boolean): Range => {
  const r = doc.createRange()
  const t = nonBlankText(node, atEnd) ?? nonBlankText(doc.body ?? doc.documentElement, atEnd)
  if (!t) { r.selectNodeContents(doc.body ?? doc.documentElement); return r }
  const v = t.nodeValue ?? ''
  let i = atEnd ? v.trimEnd().length - 1 : v.length - v.trimStart().length
  i = Math.max(0, Math.min(i, v.length - 1))
  r.setStart(t, i); r.setEnd(t, i + 1)
  return r
}

/**
 * C-009: the nearest valid position for a content path that may no longer resolve (the file changed in place).
 * Walks the element steps (even indexes) as far as they resolve. A step past the last child, or a text offset past
 * the end, lands at the end of the deepest resolved node; a missing node in the middle lands at its start. A path
 * that resolves fully keeps its exact text offset.
 */
export function clampToRange(doc: Document, steps: Step[]): Range {
  let node: Element = doc.documentElement
  for (let s = 0; s < steps.length; s++) {
    const { index, offset } = steps[s]
    const kids = Array.from(node.children)
    if (index % 2 === 0) {
      const k = index / 2 - 1
      if (k >= 0 && k < kids.length) { node = kids[k]; continue }
      return edgeRange(doc, node, k >= kids.length)
    }
    // odd step: the text between element children (k-1) and k, possibly split over several text nodes
    const k = (index - 1) / 2
    const before = k > 0 ? kids[k - 1] : null
    if (k > kids.length) return edgeRange(doc, node, true)
    const texts: Text[] = []
    for (let n = before ? before.nextSibling : node.firstChild; n && n.nodeType !== Node.ELEMENT_NODE; n = n.nextSibling)
      if (n.nodeType === Node.TEXT_NODE || n.nodeType === Node.CDATA_SECTION_NODE) texts.push(n as Text)
    let left = offset ?? 0
    for (const t of texts) {
      const len = t.nodeValue?.length ?? 0
      if (left <= len && len > 0) {
        if (left === len) break // at the very end of the text: same as past it
        const r = doc.createRange(); r.setStart(t, left); r.setEnd(t, Math.min(len, left + 1)); return r
      }
      left -= len
    }
    return edgeRange(doc, before ?? node, true)
  }
  return edgeRange(doc, node, false)
}

const hasBoxes = (x: { getClientRects(): ArrayLike<{ width: number; height: number }> }) =>
  Array.from(x.getClientRects()).some(r => r.width > 0 || r.height > 0)

/**
 * An anchor foliate can scroll to. foliate scrolls to the first box of a range; a range with no boxes (only an image or
 * an empty element, e.g. a position saved on a cover page) makes it do nothing at all, and the open would never
 * report a location. Such a range becomes its nearest element with boxes, else the section start (0).
 */
export function laidOut(r: Range): Range | Element | number {
  if (hasBoxes(r)) return r
  const start = r.startContainer
  for (let el: Element | null = start.nodeType === 1 ? (start as Element) : start.parentElement; el; el = el.parentElement) {
    if (el === el.ownerDocument.body || el === el.ownerDocument.documentElement) break
    if (hasBoxes(el)) return el
  }
  return 0
}

/**
 * foliate target for a saved position: the section of its spine step, anchored at the exact place if it still
 * resolves, otherwise at the nearest valid place in the same section (C-009). Null if the spine step itself is gone.
 */
export function resolveSaved(book: any, cfi: string): { index: number; anchor: (doc: Document) => Range | Element | number } | null {
  let resolved: { index: number; anchor: (doc: Document) => Range } | null = null
  try { resolved = book.resolveCFI(cfi) } catch { return null }
  if (!resolved || !(resolved.index >= 0) || resolved.index >= (book.sections?.length ?? 0)) return null
  const steps = contentSteps(cfi)
  const exact = resolved.anchor
  return {
    index: resolved.index,
    anchor: (doc: Document) => {
      try {
        const r = exact(doc)
        if (r && r.startContainer?.isConnected && doc.contains(r.startContainer)) return laidOut(r)
      } catch { /* falls back below */ }
      return laidOut(steps ? clampToRange(doc, steps) : edgeRange(doc, doc.body ?? doc.documentElement, false))
    },
  }
}
