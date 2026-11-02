/** How far from the indexed offset the needle is looked for: the DOM text may drift a little from the indexed text. */
const DRIFT = 500
const SKIP = new Set(['script', 'style', 'template'])

/**
 * Length-preserving fold: lowercase and no diacritics, one output char per input char (so offsets map 1:1). The
 * search index folds the same way (unicode61 remove_diacritics), but it only decides where hits are; this only verifies.
 */
export function foldText(s: string): string {
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const c = s[i].normalize('NFD')[0]
    const l = c.toLowerCase()
    out += l.length === 1 ? l : c
  }
  return out
}

/** Text and CDATA nodes under body outside script/style/template, in document order (the indexed text's source). */
export function textNodes(doc: Document): Text[] {
  if (!doc.body) return []
  const out: Text[] = []
  const w = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_CDATA_SECTION, {
    acceptNode(n) {
      for (let e = n.parentElement; e && e !== doc.body; e = e.parentElement)
        if (SKIP.has(e.localName.toLowerCase())) return NodeFilter.FILTER_REJECT
      return NodeFilter.FILTER_ACCEPT
    },
  })
  while (w.nextNode()) out.push(w.currentNode as Text)
  return out
}

/**
 * The range of a search hit in a section document: the text at [offset] if it matches [needle] (folded), else the
 * nearest folded match within ±500 chars. Null when there is none. May span several text nodes.
 */
export function resolveTextRange(doc: Document, offset: number, _length: number, needle: string): Range | null {
  const nodes = textNodes(doc)
  const text = nodes.map(n => n.data).join('')
  const want = foldText(needle)
  if (!want) return null
  // Only the window that can match is folded (the fold keeps lengths, so positions stay exact).
  const lo = Math.max(0, offset - DRIFT)
  const folded = foldText(text.slice(lo, offset + want.length + DRIFT))
  const at = (i: number) => i >= lo && folded.startsWith(want, i - lo)
  let start = -1
  if (at(offset)) start = offset
  else {
    for (let d = 1; d <= DRIFT && start < 0; d++) {
      if (at(offset - d)) start = offset - d
      else if (at(offset + d)) start = offset + d
    }
  }
  if (start < 0) return null
  const end = start + want.length
  const r = doc.createRange()
  let pos = 0, started = false
  for (const n of nodes) {
    const next = pos + n.data.length
    if (!started && start < next) { r.setStart(n, start - pos); started = true }
    if (started && end <= next) { r.setEnd(n, end - pos); return r }
    pos = next
  }
  return null
}

/**
 * C-083: where each text node starts in the section's text (the same text as above, so the same coordinates as a
 * search hit's offset and the app's canonical text). Built once per document; the reader never changes book text.
 */
type TextIndex = { nodes: Text[]; starts: number[]; at: Map<Node, number>; length: number }
const indexes = new WeakMap<Document, TextIndex>()
function textIndex(doc: Document): TextIndex {
  let ix = indexes.get(doc)
  if (ix) return ix
  const nodes = textNodes(doc)
  const starts: number[] = []
  const at = new Map<Node, number>()
  let pos = 0
  nodes.forEach((n, i) => { starts.push(pos); at.set(n, i); pos += n.data.length })
  ix = { nodes, starts, at, length: pos }
  indexes.set(doc, ix)
  return ix
}

/**
 * C-083: the text offset of a boundary point ([container], [offset]) in its document: inside a text node, its start plus
 * [offset]; elsewhere, the start of the first text node after the point (the text length when there is none).
 */
export function textOffsetOf(doc: Document, container: Node, offset: number): number {
  const ix = textIndex(doc)
  const i = ix.at.get(container)
  if (i != null) return ix.starts[i] + Math.min(offset, ix.nodes[i].data.length)
  const point = doc.createRange()
  point.setStart(container, offset)
  // first text node not before the point (nodes are in document order)
  let lo = 0, hi = ix.nodes.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (point.comparePoint(ix.nodes[mid], 0) < 0) lo = mid + 1
    else hi = mid
  }
  return lo < ix.nodes.length ? ix.starts[lo] : ix.length
}

/** C-083: the first and the end text offsets of [range] (a visible page's range). */
export function textSpanOf(range: Range): { textOffset: number; textEnd: number } | null {
  const doc = range.startContainer?.ownerDocument ?? (range.startContainer as Document)
  if (!doc?.body) return null
  return { textOffset: textOffsetOf(doc, range.startContainer, range.startOffset), textEnd: textOffsetOf(doc, range.endContainer, range.endOffset) }
}

/**
 * C-083 go to page: a one-character range at text [offset] (a page's start): the first non-whitespace character at or
 * after it, so the page's text is what the jump shows; null when the section has no text there.
 */
export function rangeAtOffset(doc: Document, offset: number): Range | null {
  const ix = textIndex(doc)
  let lo = 0, hi = ix.nodes.length - 1, k = -1
  while (lo <= hi) { const mid = (lo + hi) >> 1; if (ix.starts[mid] <= offset) { k = mid; lo = mid + 1 } else hi = mid - 1 }
  for (let i = Math.max(0, k); i < ix.nodes.length; i++) {
    const n = ix.nodes[i]
    const from = Math.max(0, offset - ix.starts[i])
    const m = /\S/.exec(n.data.slice(from))
    if (!m) continue
    const r = doc.createRange()
    r.setStart(n, from + m.index); r.setEnd(n, from + m.index + 1)
    return r
  }
  return null
}
