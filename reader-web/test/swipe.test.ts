import { describe, expect, it } from 'vitest'
import { dragFinishes, effectiveDir, followsFinger, swipeToTurn } from '../src/reader/swipe'

describe('swipeToTurn (F-032)', () => {
  it('LTR: finger moving left is next, moving right is previous', () => {
    expect(swipeToTurn(-200, 10, 150, 'ltr')).toBe('next')
    expect(swipeToTurn(200, -10, 150, 'ltr')).toBe('prev')
  })
  it('RTL: mirrored, finger moving right is next', () => {
    expect(swipeToTurn(200, 0, 150, 'rtl')).toBe('next')
    expect(swipeToTurn(-200, 0, 150, 'rtl')).toBe('prev')
  })
  it('a tap or a jitter is no swipe', () => {
    expect(swipeToTurn(0, 0, 80, 'ltr')).toBeNull()
    expect(swipeToTurn(-8, 3, 60, 'ltr')).toBeNull()
  })
  it('a long press without movement is no swipe', () => {
    expect(swipeToTurn(-5, 0, 900, 'ltr')).toBeNull()
  })
  it('a slow drag released back near the start cancels', () => {
    expect(swipeToTurn(-20, 0, 1200, 'ltr')).toBeNull()
  })
  it('a short fast flick turns', () => {
    expect(swipeToTurn(-30, 2, 60, 'ltr')).toBe('next')
  })
  it('a long slow drag turns', () => {
    expect(swipeToTurn(-120, 10, 1500, 'ltr')).toBe('next')
  })
  it('a mostly vertical movement is no horizontal turn', () => {
    expect(swipeToTurn(-60, 200, 150, 'ltr')).toBeNull()
    expect(swipeToTurn(-60, 70, 150, 'ltr')).toBeNull()
  })
  it('vertical writing: an upward swipe is next, downward previous (foliate pages it top to bottom), horizontal per dir', () => {
    expect(swipeToTurn(0, -200, 150, 'rtl', true)).toBe('next')
    expect(swipeToTurn(5, 200, 150, 'rtl', true)).toBe('prev')
    expect(swipeToTurn(200, 0, 150, 'rtl', true)).toBe('next')
    expect(swipeToTurn(0, -200, 150, 'ltr', false)).toBeNull()
  })
  it('bad input is no swipe', () => {
    expect(swipeToTurn(NaN, 0, 100, 'ltr')).toBeNull()
    expect(swipeToTurn(-200, 0, 0, 'ltr')).toBe('next') // dt 0 (same timestamp): distance alone decides
  })
})

describe('effectiveDir (F-032, F-081)', () => {
  it('auto follows the book, forced values win', () => {
    expect(effectiveDir('auto', 'rtl')).toBe('rtl')
    expect(effectiveDir('auto', 'ltr')).toBe('ltr')
    expect(effectiveDir('auto', undefined)).toBe('ltr')
    expect(effectiveDir(undefined, 'rtl')).toBe('rtl')
    expect(effectiveDir('ltr', 'rtl')).toBe('ltr')
    expect(effectiveDir('rtl', 'ltr')).toBe('rtl')
  })
})

describe('reportSwipes (fixed layout: foliate-fxl reports no swipes; F-032 for FXL books)', () => {
  const touch = (target: EventTarget, type: string, x: number, y: number, t: number, fingers = 1) => {
    const e = new Event(type) as any
    Object.defineProperty(e, 'changedTouches', { value: [{ screenX: x, screenY: y }] })
    Object.defineProperty(e, 'touches', { value: type === 'touchend' ? [] : Array(fingers).fill({ screenX: x, screenY: y }) })
    Object.defineProperty(e, 'timeStamp', { value: t })
    target.dispatchEvent(e)
  }
  it('a finished one-finger gesture in the page becomes one swipe event on the renderer', async () => {
    const { reportSwipes } = await import('../src/reader/swipe')
    const doc = document.implementation.createHTMLDocument('p')
    const renderer = document.createElement('div')
    const got: any[] = []
    renderer.addEventListener('swipe', (e: Event) => got.push((e as CustomEvent).detail))
    reportSwipes(doc, renderer)
    touch(doc, 'touchstart', 300, 400, 1000)
    touch(doc, 'touchmove', 200, 402, 1050)
    touch(doc, 'touchend', 100, 405, 1120)
    expect(got).toEqual([{ dx: -200, dy: 5, dt: 120 }])
  })
  it('two fingers, or an end without a start, report nothing', async () => {
    const { reportSwipes } = await import('../src/reader/swipe')
    const doc = document.implementation.createHTMLDocument('p')
    const renderer = document.createElement('div')
    const got: any[] = []
    renderer.addEventListener('swipe', (e: Event) => got.push(e))
    reportSwipes(doc, renderer)
    touch(doc, 'touchend', 100, 400, 900)
    touch(doc, 'touchstart', 300, 400, 1000)
    touch(doc, 'touchmove', 200, 400, 1050, 2)
    touch(doc, 'touchend', 100, 400, 1100)
    expect(got).toEqual([])
  })
})

describe('dragFinishes (C-094): a released drag finishes its turn past about a third of the screen or as a flick', () => {
  const size = 390
  const release = (main: number, speed: number, cross = 0) => dragFinishes({ main, cross, speed, size })
  it('a slow release before a third snaps back, past it finishes', () => {
    expect(release(size / 3 - 1, 0)).toBe(false)
    expect(release(size / 3, 0)).toBe(true)
    expect(release(300, 0.1)).toBe(true)
  })
  it('a flick finishes however short, at F-032\'s distance and speed', () => {
    expect(release(24, 0.3)).toBe(true)
    expect(release(23, 2)).toBe(false)
    expect(release(40, 0.29)).toBe(false)
  })
  it('a flick back against the drag snaps back, however far the page was', () => {
    expect(release(300, -0.3)).toBe(false)
    expect(release(300, -0.29)).toBe(true)
  })
  it('a release that ended up mostly across the axis snaps back', () => {
    expect(release(200, 0, 140)).toBe(false)
    expect(release(200, 1, 140)).toBe(false)
    expect(release(210, 0, 140)).toBe(true)
  })
  it('nothing dragged toward the turn finishes nothing', () => {
    expect(release(0, 3)).toBe(false)
    expect(release(-200, 3)).toBe(false)
  })
  it('unknown numbers finish nothing', () => {
    expect(release(NaN, 0)).toBe(false)
    expect(dragFinishes({ main: 200, cross: 0, speed: NaN, size: 0 })).toBe(false)
  })
})

describe('followsFinger (C-094): the page can follow only where a finger going forward moves the page forward', () => {
  it('the swipe direction in effect matches the way the pages lie', () => {
    expect(followsFinger('ltr', false, false)).toBe(true)
    expect(followsFinger('rtl', true, false)).toBe(true)
  })
  it('a swipe direction forced against the pages turns on release instead', () => {
    expect(followsFinger('rtl', false, false)).toBe(false)
    expect(followsFinger('ltr', true, false)).toBe(false)
  })
  it('top-to-bottom pages follow along their own axis whatever the setting', () => {
    expect(followsFinger('rtl', false, true)).toBe(true)
  })
})
