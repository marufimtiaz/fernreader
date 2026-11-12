import { describe, expect, it } from 'vitest'
import { dragProgress, fingerSpeed, resisted, RESIST_MAX, settleMs, SLIDE_MS, slideFrame, slideSign } from '../vendor/foliate-js/paginator.js'

// Local patch (C-094, PATCHES.md): the decisions of a finger-following turn.
describe('dragProgress: the finger\'s movement as progress toward the next page', () => {
  it('left-to-right: a finger moving left is forward, moving right is back', () => {
    expect(dragProgress(-100, 400, false)).toBe(0.25)
    expect(dragProgress(100, 400, false)).toBe(-0.25)
  })
  it('right-to-left: mirrored', () => {
    expect(dragProgress(100, 400, true)).toBe(0.25)
    expect(dragProgress(-100, 400, true)).toBe(-0.25)
  })
  it('stops at one page either way', () => {
    expect(dragProgress(-900, 400, false)).toBe(1)
    expect(dragProgress(900, 400, false)).toBe(-1)
  })
  it('agrees with the way a turn moves the content', () => {
    for (const rtl of [false, true]) for (const dir of [1, -1] as const) {
      const delta = slideFrame(0.25, slideSign(dir, rtl), 400).old // the page goes with the finger
      expect(dragProgress(delta, 400, rtl)).toBe(dir * 0.25)
    }
  })
  it('no size is no progress', () => expect(dragProgress(-100, 0, false)).toBe(0))
})

describe('resisted: the page at an edge moves less and less, and never past a limit', () => {
  it('starts at nothing, grows, stays under the limit', () => {
    expect(resisted(0)).toBe(0)
    const steps = [0.1, 0.3, 0.6, 1].map(resisted)
    for (let i = 1; i < steps.length; i++) expect(steps[i]).toBeGreaterThan(steps[i - 1])
    expect(steps[3]).toBeLessThan(RESIST_MAX)
    expect(resisted(0.3)).toBeLessThan(0.3)
  })
})

describe('fingerSpeed: how fast the finger moved just before it lifted', () => {
  it('over the last 100 ms, not the whole gesture', () => {
    const trail = [{ d: 0, t: 0 }, { d: 10, t: 900 }, { d: 20, t: 950 }, { d: 70, t: 1000 }]
    expect(fingerSpeed(trail)).toBeCloseTo(0.6)
  })
  it('a finger held still before it lifts has no speed, whatever came before', () => {
    expect(fingerSpeed([{ d: 0, t: 0 }, { d: 300, t: 60 }, { d: 300, t: 500 }])).toBe(0)
  })
  it('a gesture shorter than the window counts from its start', () => {
    expect(fingerSpeed([{ d: 0, t: 1000 }, { d: -60, t: 1040 }])).toBeCloseTo(-1.5)
  })
  it('moves far apart (a slow frame) still give a speed', () => {
    expect(fingerSpeed([{ d: 0, t: 0 }, { d: 100, t: 120 }, { d: 200, t: 240 }])).toBeCloseTo(100 / 120)
  })
  it('one sample, or no time, is no speed', () => {
    expect(fingerSpeed([{ d: 5, t: 1 }])).toBe(0)
    expect(fingerSpeed([{ d: 0, t: 7 }, { d: 50, t: 7 }])).toBe(0)
  })
})

describe('settleMs: the rest of a turn takes its share of a slide', () => {
  it('a whole page is a whole slide', () => expect(settleMs(1)).toBe(SLIDE_MS))
  it('half a page, half', () => expect(settleMs(0.5)).toBe(SLIDE_MS / 2))
  it('never less than a few frames, never more than a slide', () => {
    expect(settleMs(0.01)).toBe(60)
    expect(settleMs(2)).toBe(SLIDE_MS)
  })
})
