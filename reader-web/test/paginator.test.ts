import { describe, expect, it, vi } from 'vitest'
import { heldShift, pushRing, View, pagedVerticalRl, scrolledInlineInset, verticalPagesRootStyles } from '../vendor/foliate-js/paginator.js'

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

// C-072 (local patch): scrolled flow uses paged flow's side inset, so both flows have the same text column on a phone.
describe('scrolledInlineInset: the scrolled text column equals the paged one', () => {
  /** Paged geometry of the grid at viewport width v and gap fraction g, phone portrait (the 1fr columns at their minimum). */
  const paged = (v: number, g: number) => {
    const half = (g / 2) * v // the half-gap grid columns (percent of #top)
    const container = v - 4 * half + 2 * half // columns 2..4: half-gap + centre + half-gap
    const gap = (-g / (g - 1)) * container // foliate's column gap
    return { probeStart: half, probeSize: container, gap, textStart: half + gap / 2, textWidth: container - gap }
  }
  for (const [v, m] of [[411, 5], [411, 0], [411, 10], [360, 3], [600, 8]]) {
    it(`viewport ${v} px, margins ${m} %`, () => {
      const g = (2 * m) / 100
      const p = paged(v, g)
      const inset = scrolledInlineInset({ probeStart: p.probeStart, probeSize: p.probeSize, g })
      expect(inset).toBeCloseTo(p.textStart, 6)
      expect(v - 2 * inset).toBeCloseTo(p.textWidth, 6)
      // foliate's own scrolled padding (the C-072 bug) was wider by g / (1 - g) * v - g * v per side
      if (m > 0) expect((-g / (g - 1)) * v).toBeGreaterThan(inset)
    })
  }
  it('the measured emulator case: 411 px, 5 %: 41.14 px per side, a 329.14 px column (scrolled had 45.71 / 320)', () => {
    const inset = scrolledInlineInset({ probeStart: 20.55, probeSize: 369.9, g: 0.1 })
    expect(inset).toBeCloseTo(41.1, 1)
    expect(411 - 2 * inset).toBeCloseTo(328.8, 0)
  })
})

describe('heldShift: the text on screen is held in stack mode, the user\'s scrolling is not undone', () => {
  const screen = { top: -100, scrollTop: 5000 }
  it('nothing changed: no shift', () => expect(heldShift(screen, -100, 5000)).toBe(0))
  it('the user scrolled 48 px since the capture, nothing grew: no shift', () => expect(heldShift(screen, -148, 5048)).toBe(0))
  it('something above grew by 30 px: the shift is 30', () => expect(heldShift(screen, -70, 5000)).toBe(30))
  it('both: only the growth counts', () => expect(heldShift(screen, -118, 5048)).toBe(30))
  it('a slot above was dropped and scrollTop already followed (the capture moved with it): no shift', () =>
    expect(heldShift({ top: -100, scrollTop: 5000 - 786 }, -100, 5000 - 786)).toBe(0))
})

describe('a view dropped while it loads (a jump evicts the stacked sections)', () => {
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  const loading = () => {
    const view = new View({ container: document.createElement('div'), onExpand: () => {} })
    document.body.append(view.element)
    const frame = view.element.querySelector('iframe')!
    Object.defineProperty(frame, 'contentDocument', { value: { body: null } }) // mid-load: a document with no body yet
    return view
  }
  it('destroy does not throw on a document with no body', () => {
    expect(() => loading().destroy()).not.toThrow()
  })
  it('destroy releases whoever waits for the load: a removed frame never fires `load`', async () => {
    const view = loading()
    const load = view.load('blob:never', () => {}, () => ({}))
    view.destroy()
    await expect(load).resolves.toBeUndefined()
  })
})

describe('pushRing: the test trace keeps the newest entries', () => {
  it('drops the oldest past the cap', () => {
    const ring: number[] = []
    for (let i = 0; i < 5; i++) pushRing(ring, i, 3)
    expect(ring).toEqual([2, 3, 4])
  })
})
