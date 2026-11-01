import { describe, expect, it } from 'vitest'
import { pagedVerticalRl, verticalPagesRootStyles } from '../vendor/foliate-js/paginator.js'

// Local patch (F-081, PATCHES.md): vertical-rl sections in paginated flow are laid out as horizontal pages moving
// right-to-left (CSS multicol rows, `column-wrap: wrap`), not foliate's top-to-bottom vertical pages.
describe('pagedVerticalRl: which documents get right-to-left horizontal pages', () => {
  const layout = { width: 400, height: 800, gap: 28, columnWidth: 372, margin: 32, verticalPages: true }
  it('vertical-rl in paginated flow when the layout asks for vertical pages', () => {
    expect(pagedVerticalRl(layout, 'vertical-rl')).toBe(true)
  })
  it('not horizontal or vertical-lr documents', () => {
    expect(pagedVerticalRl(layout, 'horizontal-tb')).toBe(false)
    expect(pagedVerticalRl(layout, 'vertical-lr')).toBe(false)
  })
  it('not in scrolled flow (foliate scrolls vertical books sideways as before)', () => {
    expect(pagedVerticalRl({ ...layout, flow: 'scrolled' }, 'vertical-rl')).toBe(false)
  })
  it('not when the engine lacks column-wrap (the layout does not ask for it): foliate vertical pages', () => {
    expect(pagedVerticalRl({ ...layout, verticalPages: false }, 'vertical-rl')).toBe(false)
    expect(pagedVerticalRl(undefined, 'vertical-rl')).toBe(false)
  })
})

describe('verticalPagesRootStyles: one column per page, pages stacked as multicol rows from right to left', () => {
  it('page height is the inline size, each row a page wide minus the gap, gap between and half around', () => {
    const s = verticalPagesRootStyles({ height: 800, gap: 28, columnWidth: 372 })
    expect(s['writing-mode']).toBe('vertical-rl')
    expect(s['height']).toBe('800px')
    expect(s['width']).toBe('auto')
    expect(s['column-count']).toBe('1')
    expect(s['column-height']).toBe('372px')
    expect(s['column-wrap']).toBe('wrap')
    expect(s['row-gap']).toBe('28px')
    expect(s['padding']).toBe('0 14px')
    expect(s['column-fill']).toBe('auto')
  })
  it('two pages per screen (spread): each row is half the width minus the gap', () => {
    const s = verticalPagesRootStyles({ height: 400, gap: 20, columnWidth: 380 })
    expect(s['column-height']).toBe('380px')
  })
  it('the row is exactly the page width minus the gap (not truncated: a fraction per page adds up across a chapter)', () => {
    const s = verticalPagesRootStyles({ height: 850.25, gap: 41.125, columnWidth: 329.25 })
    expect(s['column-height']).toBe('329.25px')
    expect(s['height']).toBe('850.25px')
  })
})

// Local patch (C-071, PATCHES.md): a turn into the preloaded neighbour section slides like a turn inside a section.
describe('boundary slide (C-071): the old page leaves and the neighbour page comes in, side by side', () => {
  it('direction: next moves content toward the line start (left in LTR, right in RTL), prev the other way', async () => {
    const { slideSign } = await import('../vendor/foliate-js/paginator.js')
    expect(slideSign(1, false)).toBe(-1)
    expect(slideSign(-1, false)).toBe(1)
    expect(slideSign(1, true)).toBe(1)
    expect(slideSign(-1, true)).toBe(-1)
  })
  it('the incoming page starts one width away and ends in place; the two pages stay exactly adjacent (no gap, no blank)', async () => {
    const { slideFrame, slideSign } = await import('../vendor/foliate-js/paginator.js')
    for (const [dir, rtl] of [[1, false], [-1, false], [1, true], [-1, true]] as const) {
      const s = slideSign(dir, rtl)
      expect(slideFrame(0, s, 400)).toEqual({ old: 0, next: -s * 400 })
      expect(slideFrame(1, s, 400)).toEqual({ old: s * 400, next: 0 })
      for (const t of [0.1, 0.37, 0.5, 0.9]) {
        const f = slideFrame(t, s, 400)
        expect(f.old - f.next).toBeCloseTo(s * 400)
      }
    }
  })
})
