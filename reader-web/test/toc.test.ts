import { describe, expect, it } from 'vitest'
import { flattenToc } from '../src/reader/toc'

const book = {
  toc: [
    { label: ' Part 1 ', href: 'p1.xhtml', subitems: [{ label: 'Chapter 1', href: 'c1.xhtml#a' }] },
    { label: 'Gone', href: 'gone.xhtml' },
    { label: 'Not in spine', href: 'extra.xhtml' },
  ],
  sections: [{ id: 'p1.xhtml' }, { id: 'c1.xhtml' }, { id: 'gone.xhtml' }],
  resolveHref(href: string) {
    const i = this.sections.findIndex(s => s.id === href.split('#')[0])
    return i < 0 ? null : { index: i }
  },
}
const has = (n: string) => n !== 'gone.xhtml'

describe('flattenToc', () => {
  it('flattens with depth, trims labels and flags missing targets (R-001)', () => {
    expect(flattenToc(book, has)).toEqual([
      { label: 'Part 1', href: 'p1.xhtml', depth: 0, missing: false, index: 0 },
      { label: 'Chapter 1', href: 'c1.xhtml#a', depth: 1, missing: false, index: 1 },
      { label: 'Gone', href: 'gone.xhtml', depth: 0, missing: true, index: 2 },
      { label: 'Not in spine', href: 'extra.xhtml', depth: 0, missing: true, index: -1 },
    ])
  })
})
