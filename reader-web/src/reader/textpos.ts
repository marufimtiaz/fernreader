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
function textNodes(doc: Document): Text[] {
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
