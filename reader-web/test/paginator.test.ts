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
})
