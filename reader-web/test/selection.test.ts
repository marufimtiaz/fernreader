import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readSelection, SelectionReporter, type SelectionInfo } from '../src/reader/selection'
import { annotationDraw, HIGHLIGHT_COLORS } from '../src/reader/annotations'

/** A section document with a browsing context (an iframe), as foliate shows them; parsed documents have no Selection. */
function html(body: string): Document {
  const f = document.createElement('iframe'); document.body.append(f)
  const d = f.contentDocument!; d.body.innerHTML = body
  return d
}

/** Selects [start, end) of the first text node under the element matching [sel]. */
function select(doc: Document, sel: string, start: number, end: number) {
  const t = doc.querySelector(sel)!.firstChild!
  const r = doc.createRange(); r.setStart(t, start); r.setEnd(t, end)
  const s = doc.getSelection()!; s.removeAllRanges(); s.addRange(r)
}

describe('readSelection (F-060)', () => {
  const cfiOf = (index: number, r: Range) => `cfi:${index}:${r.startOffset}-${r.endOffset}`

  it('takes the exact text of the selection (Selection.toString), its range CFI and section', () => {
    const doc = html('<p id="a">  The quick brown fox  </p>')
    select(doc, '#a', 1, 12)
    expect(readSelection([{ doc, index: 3 }], cfiOf)).toEqual({ text: ' The quick ', cfi: 'cfi:3:1-12', sectionIndex: 3 })
  })
  it('a collapsed or empty selection is none', () => {
    const doc = html('<p id="a">word</p>')
    expect(readSelection([{ doc, index: 0 }], cfiOf)).toBeNull()
    select(doc, '#a', 2, 2)
    expect(readSelection([{ doc, index: 0 }], cfiOf)).toBeNull()
  })
  it('the selection may be in any loaded section (scrolled flow shows several)', () => {
    const a = html('<p id="a">first</p>'); const b = html('<p id="b">second</p>')
    select(b, '#b', 0, 3)
    expect(readSelection([{ doc: a, index: 1 }, { doc: b, index: 2 }], cfiOf)).toEqual({ text: 'sec', cfi: 'cfi:2:0-3', sectionIndex: 2 })
  })
  it('a CFI that cannot be made is no selection (never throws)', () => {
    const doc = html('<p id="a">word</p>')
    select(doc, '#a', 0, 4)
    expect(readSelection([{ doc, index: 0 }], () => { throw new Error('no cfi') })).toBeNull()
  })
})

describe('SelectionReporter (F-060)', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })
  const sel = (text: string): SelectionInfo => ({ text, cfi: `c-${text}`, sectionIndex: 0 })

  function setup() {
    let cur: SelectionInfo | null = null
    const sent: Array<SelectionInfo | null> = []
    const r = new SelectionReporter(() => cur, s => sent.push(s), 120)
    return { r, sent, set: (s: SelectionInfo | null) => { cur = s } }
  }

  it('reports once the selection has settled (debounced), not on every change', () => {
    const { r, sent, set } = setup()
    set(sel('w')); r.changed()
    vi.advanceTimersByTime(60); set(sel('wo')); r.changed()
    vi.advanceTimersByTime(60); set(sel('wor')); r.changed()
    vi.advanceTimersByTime(119)
    expect(sent).toEqual([])
    vi.advanceTimersByTime(1)
    expect(sent).toEqual([sel('wor')])
  })
  it('a collapse after a selection is reported as cleared, once', () => {
    const { r, sent, set } = setup()
    set(sel('word')); r.changed(); vi.advanceTimersByTime(120)
    set(null); r.changed(); vi.advanceTimersByTime(120)
    r.changed(); vi.advanceTimersByTime(120)
    expect(sent).toEqual([sel('word'), null])
  })
  it('no cleared message without a selection before it, and no repeat of an unchanged one', () => {
    const { r, sent, set } = setup()
    r.changed(); vi.advanceTimersByTime(200)
    set(sel('a')); r.changed(); vi.advanceTimersByTime(200)
    r.changed(); vi.advanceTimersByTime(200)
    expect(sent).toEqual([sel('a')])
  })
  it('reset forgets the selection and drops a pending report (open, close)', () => {
    const { r, sent, set } = setup()
    set(sel('a')); r.changed(); vi.advanceTimersByTime(120)
    set(sel('b')); r.changed(); r.reset(); vi.advanceTimersByTime(500)
    expect(sent).toEqual([sel('a')])
    set(null); r.changed(); vi.advanceTimersByTime(120)
    expect(sent).toEqual([sel('a')]) // nothing known after reset: no cleared message
  })
})

describe('annotationDraw (F-060 highlight and underline)', () => {
  it('six colours, highlight and underline styles', () => {
    expect(Object.keys(HIGHLIGHT_COLORS)).toEqual(['yellow', 'green', 'blue', 'pink', 'orange', 'purple'])
    expect(annotationDraw('highlight', 'green')).toEqual({ kind: 'highlight', color: HIGHLIGHT_COLORS.green })
    expect(annotationDraw('underline', 'blue')).toEqual({ kind: 'underline', color: HIGHLIGHT_COLORS.blue })
  })
  it('an unknown colour falls back to yellow', () => {
    expect(annotationDraw('highlight', 'neon').color).toBe(HIGHLIGHT_COLORS.yellow)
  })
})
