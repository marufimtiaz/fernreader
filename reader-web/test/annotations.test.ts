import { describe, expect, it } from 'vitest'
import { AnnotationStore, annotationDraw, HIGHLIGHT_COLORS, type AnnotationItem } from '../src/reader/annotations'

const item = (id: string, cfi: string, color = 'yellow', style: AnnotationItem['style'] = 'highlight'): AnnotationItem => ({ id, cfi, color, style })
/** Section index from the test CFIs "s<index>:<rest>"; anything else is unknown (-1). */
const indexOf = (cfi: string) => { const m = /^s(\d+):/.exec(cfi); return m ? Number(m[1]) : -1 }
const ids = (l: { id: string }[]) => l.map(a => a.id)

describe('AnnotationStore (F-061)', () => {
  it('replace keeps the items per section and reports what to draw and what to delete', () => {
    const s = new AnnotationStore(indexOf)
    let d = s.replace([item('a', 's1:x'), item('b', 's2:y'), item('c', 's1:z')])
    expect(ids(d.draw)).toEqual(['a', 'b', 'c'])
    expect(d.erase).toEqual([])
    expect(ids(s.inSection(1))).toEqual(['a', 'c'])
    expect(ids(s.inSection(2))).toEqual(['b'])
    expect(s.inSection(3)).toEqual([])
    // Same set again: nothing to do. One recoloured, one gone, one new.
    expect(s.replace([item('a', 's1:x'), item('b', 's2:y'), item('c', 's1:z')])).toEqual({ draw: [], erase: [] })
    d = s.replace([item('a', 's1:x', 'green'), item('c', 's1:z'), item('d', 's3:w')])
    expect(ids(d.draw)).toEqual(['a', 'd'])
    expect(d.erase).toEqual(['s2:y'])
    expect(s.byValue('s1:x')?.color).toBe('green')
    expect(s.byValue('s2:y')).toBeUndefined()
    expect(ids(s.inSection(2))).toEqual([])
  })

  it('upsert: a new id draws; the same id with a new range erases the old one first', () => {
    const s = new AnnotationStore(indexOf)
    expect(s.upsert(item('a', 's1:x'))).toEqual({ draw: [expect.objectContaining({ id: 'a' })], erase: [] })
    const d = s.upsert(item('a', 's2:y', 'pink'))
    expect(d.erase).toEqual(['s1:x'])
    expect(ids(d.draw)).toEqual(['a'])
    expect(ids(s.inSection(1))).toEqual([])
    expect(ids(s.inSection(2))).toEqual(['a'])
    expect(s.byValue('s2:y')?.color).toBe('pink')
  })

  it('remove erases its range, unless another annotation is on the identical range (that one is drawn instead)', () => {
    const s = new AnnotationStore(indexOf)
    s.replace([item('a', 's1:x', 'green'), item('b', 's1:x', 'blue'), item('c', 's1:z')])
    expect(s.byValue('s1:x')?.id).toBe('b') // the later one wins the shared overlay key
    expect(s.remove('b')).toEqual({ draw: [expect.objectContaining({ id: 'a' })], erase: [] })
    expect(s.byValue('s1:x')?.id).toBe('a')
    expect(s.remove('a')).toEqual({ draw: [], erase: ['s1:x'] })
    expect(s.remove('missing')).toEqual({ draw: [], erase: [] })
    expect(ids(s.inSection(1))).toEqual(['c'])
  })

  it('an unknown section is offered to every section until foliate tells where it is', () => {
    const s = new AnnotationStore(indexOf)
    s.replace([item('a', 'odd'), item('b', 's4:x')])
    expect(ids(s.inSection(7))).toEqual(['a'])
    s.located('odd', 7)
    expect(ids(s.inSection(7))).toEqual(['a'])
    expect(ids(s.inSection(4))).toEqual(['b'])
    expect(ids(s.inSection(5))).toEqual([])
  })

  it('hit finds the latest annotation whose drawn range covers the point, in that document only', () => {
    const s = new AnnotationStore(indexOf)
    s.replace([item('a', 's1:x'), item('b', 's1:y'), item('c', 's2:z')])
    const doc = document.implementation.createHTMLDocument('x')
    const other = document.implementation.createHTMLDocument('y')
    const range = (d: Document, rects: { left: number; top: number; right: number; bottom: number }[]) =>
      ({ startContainer: d.body, getClientRects: () => rects }) as unknown as Range
    s.drawn('s1:x', range(doc, [{ left: 0, top: 0, right: 100, bottom: 20 }]))
    s.drawn('s1:y', range(doc, [{ left: 50, top: 0, right: 150, bottom: 20 }]))
    s.drawn('s2:z', range(other, [{ left: 0, top: 0, right: 500, bottom: 500 }]))
    expect(s.hit(1, doc, 10, 10)?.id).toBe('a')
    expect(s.hit(1, doc, 60, 10)?.id).toBe('b') // overlap: the later one, as it is drawn on top
    expect(s.hit(1, doc, 10, 30)).toBeNull()
    expect(s.hit(2, doc, 10, 10)).toBeNull() // c's range lives in another document
    expect(s.hit(2, other, 10, 10)?.id).toBe('c')
  })

  it('clear forgets everything', () => {
    const s = new AnnotationStore(indexOf)
    s.replace([item('a', 's1:x')])
    s.clear()
    expect(s.inSection(1)).toEqual([])
    expect(s.byValue('s1:x')).toBeUndefined()
  })
})

describe('colours', () => {
  it('six colours by id; an unknown id draws yellow', () => {
    expect(Object.keys(HIGHLIGHT_COLORS)).toEqual(['yellow', 'green', 'blue', 'pink', 'orange', 'purple'])
    expect(annotationDraw('underline', 'nope')).toEqual({ kind: 'underline', color: HIGHLIGHT_COLORS.yellow })
  })
})
