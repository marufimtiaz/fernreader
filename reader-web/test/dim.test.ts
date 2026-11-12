import { describe, expect, it } from 'vitest'
import { dimColor, dimFactor, dimFilter, DIM_MIN, pagePaper, THEMES } from '../src/reader/styles'
import { vi } from 'vitest'
import { Bridge } from '../src/bridge/bridge'

// C-110 Extra dim: the page's lit pixels at [dim] of their level (0.7 .. 0.15), black stays black.
describe('dimFactor', () => {
  it('is 1 (off) when absent, 1 or above, or not a number', () => {
    for (const d of [undefined, 1, 1.5, NaN]) expect(dimFactor(d)).toBe(1)
  })
  it('keeps a level inside 0.15 .. 1 and clamps one below the floor', () => {
    expect(dimFactor(0.7)).toBe(0.7)
    expect(dimFactor(0.42)).toBe(0.42)
    expect(dimFactor(0.15)).toBe(0.15)
    expect(dimFactor(0.01)).toBe(DIM_MIN)
    expect(dimFactor(0)).toBe(DIM_MIN)
  })
})

describe('dimColor', () => {
  it('scales each sRGB channel by the factor and rounds', () => {
    expect(dimColor('#FBFAF7', 0.5)).toBe('#7e7d7c') // 251*.5=125.5 -> 126, 250 -> 125, 247 -> 123.5 -> 124
    expect(dimColor('#C4C0B8', 0.15)).toBe('#1d1d1c') // 196 -> 29.4, 192 -> 28.8, 184 -> 27.6
    expect(dimColor('#ffffff', 0.7)).toBe('#b3b3b3')
  })
  it('keeps true black black at every level', () => {
    for (const f of [0.7, 0.5, 0.15]) expect(dimColor('#000000', f)).toBe('#000000')
  })
  it('returns the colour unchanged when off', () => {
    expect(dimColor('#F4E9D3', 1)).toBe('#F4E9D3')
  })
  it('leaves anything that is not #rrggbb as it is', () => {
    expect(dimColor('currentColor', 0.5)).toBe('currentColor')
    expect(dimColor('', 0.5)).toBe('')
  })
})

describe('dimFilter', () => {
  it('is none when off (no filter at all), brightness(f) otherwise', () => {
    expect(dimFilter(1)).toBe('none')
    expect(dimFilter(0.5)).toBe('brightness(0.5)')
    expect(dimFilter(0.15)).toBe('brightness(0.15)')
  })
})

describe('pagePaper', () => {
  it('is the theme palette, scaled like the filter scales the page', () => {
    const p = pagePaper('warm', 0.5)
    expect(p.bg).toBe(dimColor(THEMES.warm.bg, 0.5))
    expect(p.fg).toBe(dimColor(THEMES.warm.fg, 0.5))
    expect(p.rule).toBe(dimColor(THEMES.warm.rule, 0.5))
    expect(p.filter).toBe('brightness(0.5)')
  })
  it('is the plain palette with no filter when off', () => {
    expect(pagePaper('light', 1)).toEqual({ bg: THEMES.light.bg, fg: THEMES.light.fg, rule: THEMES.light.rule, filter: 'none' })
  })
  it('black stays black under any level', () => {
    expect(pagePaper('black', 0.15).bg).toBe('#000000')
  })
  it('an unknown theme has no paper colour', () => {
    expect(pagePaper('sepia' as any, 0.5).bg).toBe('')
  })
})

describe('bridge settings', () => {
  const s = { fontScale: 1, fontFamily: 'publisher', lineHeight: 1.5, marginPercent: 5, align: 'publisher', theme: 'black', flow: 'paginated' }
  const takes = (settings: object) => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    b.receive(JSON.stringify({ v: 1, type: 'setSettings', settings })); return h.mock.calls.length === 1
  }
  it('takes a dim level in (0, 1], and settings without one (older apps)', () => {
    expect(takes({ ...s, dim: 0.5 })).toBe(true)
    expect(takes({ ...s, dim: 1 })).toBe(true)
    expect(takes(s)).toBe(true)
  })
  it('drops a message with a dim level out of range', () => {
    for (const dim of [0, -0.2, 1.2, 'half']) expect(takes({ ...s, dim })).toBe(false)
  })
})
