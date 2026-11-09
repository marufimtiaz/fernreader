import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EDGE_STRIP, EdgeGesture, edgeAngled, edgeFraction, edgeOpens, edgeSpeed, edgeStep, inEdgeStrip, viewportPoint } from '../src/reader/edge'
import { reportSwipes } from '../src/reader/swipe'

const W = 400

describe('inEdgeStrip (C-097)', () => {
  it('is about 24 dp along the right edge, and only there', () => {
    expect(EDGE_STRIP).toBe(24)
    expect(inEdgeStrip(399, W)).toBe(true)
    expect(inEdgeStrip(W - 24, W)).toBe(true)
    expect(inEdgeStrip(W - 25, W)).toBe(false)
    expect(inEdgeStrip(5, W)).toBe(false)
  })
  it('no width, no strip', () => expect(inEdgeStrip(0, 0)).toBe(false))
})

describe('edgeStep: when the sideways part starts', () => {
  const start = { x: 390, y: 300, first: true }
  /** Runs the finger through [points]; the index of the point that started the sideways part, or -1. */
  const firstSideways = (points: [number, number][]) => {
    let anchor = start
    for (const [i, [x, y]] of points.entries()) {
      const s = edgeStep(anchor, x, y)
      if (s.sideways) return i
      anchor = s.anchor
    }
    return -1
  }
  it('inside the slop nothing is decided', () => {
    expect(edgeStep(start, 386, 302)).toEqual({ anchor: start, sideways: false })
  })
  it('straight left: on the first move, the only one that can stop a native scroll', () => {
    expect(firstSideways([[380, 300]])).toBe(0)
  })
  it('an angle steeper than 45 degrees counts: only that gets past the Back detector', () => {
    expect(firstSideways([[384, 309]])).toBe(0) // 6 left, 9 down
    expect(firstSideways([[378, 318], [366, 336]])).toBe(0)
  })
  it('up and left counts as well', () => expect(firstSideways([[384, 291]])).toBe(0))
  it('a vertical scroll never opens, nor does the drift of a thumb', () => {
    expect(firstSideways([[390, 310], [390, 340], [390, 400], [390, 500]])).toBe(-1)
    expect(firstSideways([[388, 320], [381, 350], [373, 380], [365, 410], [357, 440]])).toBe(-1)
  })
  it('down, then sideways in one go: on the first full step of the sideways part', () => {
    expect(firstSideways([[390, 330], [390, 360], [388, 362], [375, 364], [366, 365]])).toBe(4)
  })
  it('however long the scroll before it', () => {
    const scroll = Array.from({ length: 50 }, (_, i) => [390, 300 + 30 * (i + 1)] as [number, number])
    expect(firstSideways([...scroll, [360, 1800]])).toBe(50)
  })
  it('rightward is never the sideways part', () => {
    expect(firstSideways([[399, 300]])).toBe(-1)
    expect(firstSideways([[390, 330], [420, 331]])).toBe(-1)
  })
  it('moves far apart (a native scroll throttles them to about five a second) still work', () => {
    expect(firstSideways([[390, 330], [385, 430], [290, 450]])).toBe(2)
  })
})

/**
 * C-100c: swipes from the user's phone (a screen recording with the touches shown, 2.75 px per dp): where the finger was
 * every 10 ms or so, as dp from the right edge and from the top. Placed here on a 400 px wide page.
 */
const phone = (pts: [number, number][]) => pts.map(([inDp, y]) => [W - inDp, y] as [number, number])
/** 24.2 s in the recording: down and left, more than twice as far down; the steepest one. */
const steepDown = phone([[7.3, 269.5], [9.5, 288.0], [12.7, 305.8], [18.5, 324.0], [24.7, 342.5], [29.1, 358.5], [44.4, 401.5], [55.3, 427.3]])
/** 27.6 s: up and left. */
const upAndLeft = phone([[8.4, 646.9], [18.9, 623.6], [33.1, 591.6], [48.0, 559.3], [60.0, 536.4], [72.7, 512.0]])

describe('edgeAngled: an angled swipe counts once it has gone some way left (C-100c)', () => {
  const first = (points: [number, number][]) => {
    const [x0, y0] = points[0]
    return points.findIndex(([x, y]) => edgeAngled({ x: x0, y: y0 }, x, y))
  }
  it('the phone\'s swipes, which no 20 dp step of at 0.6 finds, count once 24 dp left', () => {
    expect(first(steepDown)).toBe(7) // 48 dp left, 157.8 down; at 6, 37.1 left is under 0.3 of 132 down
    expect(first(upAndLeft)).toBe(2) // 24.7 left, 55.3 up
  })
  it('not before 24 dp left, however flat', () => {
    expect(edgeAngled({ x: 390, y: 300 }, 367, 300)).toBe(false)
    expect(edgeAngled({ x: 390, y: 300 }, 366, 300)).toBe(true)
  })
  it('nearly vertical never: left must be at least 0.3 of the way down (about 17 degrees off vertical)', () => {
    expect(edgeAngled({ x: 390, y: 300 }, 360, 400)).toBe(true)
    expect(edgeAngled({ x: 390, y: 300 }, 360, 401)).toBe(false)
    expect(edgeAngled({ x: 390, y: 300 }, 360, 199)).toBe(false)
  })
  it('a thumb scrolling down the strip 15 degrees off vertical never counts', () => {
    const k = Math.tan(15 * Math.PI / 180)
    for (let d = 0; d <= 1500; d += 10) expect(edgeAngled({ x: 395, y: 0 }, 395 - k * d, d)).toBe(false)
  })
  it('rightward never', () => expect(edgeAngled({ x: 380, y: 300 }, 410, 300)).toBe(false))
})

describe('edgeFraction, edgeOpens, edgeSpeed', () => {
  it('the panel edge is under the finger', () => {
    expect(edgeFraction(300, W)).toBe(0.25)
    expect(edgeFraction(-20, W)).toBe(1)
    expect(edgeFraction(420, W)).toBe(0)
    expect(edgeFraction(100, 0)).toBe(0)
  })
  it('past a third opens, short of it goes back', () => {
    expect(edgeOpens(0.34, 0, W)).toBe(true)
    expect(edgeOpens(0.3, 0, W)).toBe(false)
  })
  it('a flick opens however short, a flick back never', () => {
    expect(edgeOpens(0.1, 0.5, W)).toBe(true)
    expect(edgeOpens(0.02, 0.5, W)).toBe(false) // under F-032's 24 px
    expect(edgeOpens(0.6, -0.5, W)).toBe(false)
  })
  it('speed is that of the last 100 ms', () => {
    expect(edgeSpeed([{ d: 0, t: 0 }, { d: 10, t: 900 }, { d: 20, t: 950 }, { d: 70, t: 1000 }])).toBeCloseTo(0.6)
    expect(edgeSpeed([])).toBe(0)
  })
})

describe('viewportPoint', () => {
  it('adds where the frame lies, scaled like the frame', () => {
    const frame = { getBoundingClientRect: () => ({ left: -800, top: 10, width: 600 }), offsetWidth: 300 }
    const target = { ownerDocument: { defaultView: { frameElement: frame } } }
    expect(viewportPoint({ target, clientX: 500, clientY: 20 } as any)).toEqual({ x: 200, y: 50 })
  })
  it('a touch in the host page is where it is', () => {
    expect(viewportPoint({ target: document.body, clientX: 5, clientY: 6 } as any)).toEqual({ x: 5, y: 6 })
  })
})

describe('EdgeGesture', () => {
  let got: [string, number, boolean][]
  let g: EdgeGesture
  let selection: { isCollapsed: boolean } | null
  const touch = (x: number, y: number) => ({
    clientX: x, clientY: y, screenX: x + 1000, screenY: y + 2000,
    target: { ownerDocument: { getSelection: () => selection, defaultView: null } },
  })
  const ev = (x: number, y: number, t = 0, fingers = 1, ended = false) =>
    ({ changedTouches: [touch(x, y)], touches: Array(ended ? 0 : fingers).fill(touch(x, y)), timeStamp: t }) as unknown as TouchEvent

  beforeEach(() => {
    vi.stubGlobal('innerWidth', W)
    got = []
    selection = null
    g = new EdgeGesture((p, f, o) => got.push([p, f, o]))
  })
  afterEach(() => vi.unstubAllGlobals())

  it('takes a touch in the strip only, and one finger only', () => {
    expect(g.down(ev(200, 300))).toBe(false)
    expect(g.move(ev(100, 300))).toBeNull()
    expect(g.up(ev(100, 300, 0, 1, true))).toBe(false)
    expect(g.down(ev(390, 300, 0, 2))).toBe(false)
    expect(g.down(ev(390, 300))).toBe(true)
  })
  it('a pinched page has no strip', () => {
    vi.stubGlobal('visualViewport', { scale: 2 })
    expect(g.down(ev(390, 300))).toBe(false)
  })
  it('a tap is no report, but it is the gesture\'s to end', () => {
    g.down(ev(390, 300))
    expect(g.up(ev(390, 300, 80, 1, true))).toBe(true)
    expect(got).toEqual([])
  })
  it('straight left: start, move, end; open past a third', () => {
    g.down(ev(390, 300, 0))
    expect(g.move(ev(380, 300, 16))).toBe('following')
    expect(g.move(ev(300, 300, 400))).toBe('following')
    expect(g.move(ev(200, 300, 800))).toBe('following')
    expect(g.up(ev(200, 300, 1200, 1, true))).toBe(true)
    expect(got.map(r => r[0])).toEqual(['start', 'move', 'move', 'end'])
    expect(got[0][1]).toBeCloseTo(0.05)
    expect(got.at(-1)).toEqual(['end', 0.5, true])
  })
  it('a short slow one goes back', () => {
    g.down(ev(390, 300, 0))
    g.move(ev(330, 300, 500))
    g.move(ev(330, 300, 1000))
    g.up(ev(330, 300, 1500, 1, true))
    expect(got.at(-1)?.[2]).toBe(false)
  })
  it('a flick opens', () => {
    g.down(ev(390, 300, 0))
    g.move(ev(370, 300, 16))
    g.move(ev(340, 300, 32))
    g.up(ev(320, 300, 48, 1, true))
    expect(got.at(-1)?.[2]).toBe(true)
  })
  it('a scroll is watched, not reported, until it goes sideways, and then the finger is reported where it is', () => {
    g.down(ev(390, 300, 0))
    expect(g.move(ev(390, 330, 16))).toBe('watching')
    expect(g.move(ev(390, 400, 32))).toBe('watching')
    expect(got).toEqual([])
    expect(g.move(ev(350, 402, 48))).toBe('following')
    expect(got).toEqual([['start', 0.125, false]])
  })
  it('a steep swipe from the phone starts it once it is 24 dp left, from the touch-down', () => {
    const [[x0, y0], ...rest] = upAndLeft
    g.down(ev(x0, y0, 0))
    const seen = rest.map(([x, y], i) => g.move(ev(x, y, 10 * (i + 1))))
    expect(seen).toEqual(['watching', 'following', 'following', 'following', 'following'])
    expect(got[0][0]).toBe('start')
  })
  it('a scroll that never turns ends silently, and it was still the gesture\'s', () => {
    g.down(ev(390, 300, 0))
    g.move(ev(390, 500, 100))
    expect(g.up(ev(390, 600, 200, 1, true))).toBe(true)
    expect(got).toEqual([])
  })
  it('once following it stays so, whatever the finger does', () => {
    g.down(ev(390, 300, 0))
    g.move(ev(350, 300, 16))
    expect(g.move(ev(350, 500, 200))).toBe('following')
    expect(g.move(ev(399, 500, 400))).toBe('following')
    expect(got.at(-1)?.[0]).toBe('move')
  })
  it('a second finger ends it: the panel goes back, and the moves are not its own any more', () => {
    g.down(ev(390, 300, 0))
    g.move(ev(250, 300, 16))
    expect(g.down(ev(100, 100, 30, 2))).toBe(false)
    expect(got.at(-1)).toEqual(['end', 0.375, false])
    expect(g.move(ev(240, 300, 40, 2))).toBeNull()
  })
  it('a pinch that begins in a move ends it too', () => {
    g.down(ev(390, 300, 0))
    g.move(ev(250, 300, 16))
    expect(g.move(ev(240, 300, 40, 2))).toBeNull()
    expect(got.at(-1)).toEqual(['end', 0.375, false])
  })
  it('a touchcancel (the system took it for Back) sends the panel back, and is silent before the panel moved', () => {
    g.down(ev(390, 300, 0))
    g.drop()
    expect(got).toEqual([])
    g.down(ev(390, 300, 0))
    g.move(ev(250, 300, 16))
    g.drop()
    expect(got.at(-1)).toEqual(['end', 0.375, false])
    g.drop()
    expect(got.length).toBe(2)
  })
  it('text being selected keeps its gesture, which still turns nothing', () => {
    selection = { isCollapsed: false }
    g.down(ev(390, 300, 0))
    expect(g.move(ev(350, 300, 16))).toBe('watching')
    expect(g.move(ev(200, 300, 32))).toBe('watching')
    expect(g.up(ev(200, 300, 48, 1, true))).toBe(true)
    expect(got).toEqual([])
  })
  it('counts its starts for the tests', () => {
    g.down(ev(390, 300, 0))
    g.move(ev(350, 300, 16))
    g.up(ev(350, 300, 900, 1, true))
    expect(g.last).toMatchObject({ phase: 'end', starts: 1, open: false })
  })
})

describe('reportSwipes with an edge gesture (fixed layout)', () => {
  const fire = (target: EventTarget, type: string, x: number, y: number, t: number) => {
    const e = new Event(type) as any
    const touch = { clientX: x, clientY: y, screenX: x, screenY: y, target: document.body }
    Object.defineProperty(e, 'changedTouches', { value: [touch] })
    Object.defineProperty(e, 'touches', { value: type === 'touchend' || type === 'touchcancel' ? [] : [touch] })
    Object.defineProperty(e, 'timeStamp', { value: t })
    target.dispatchEvent(e)
  }
  beforeEach(() => vi.stubGlobal('innerWidth', W))
  afterEach(() => vi.unstubAllGlobals())
  const setup = () => {
    const doc = document.implementation.createHTMLDocument('p')
    const renderer = document.createElement('div')
    const swipes: unknown[] = [], reports: string[] = []
    renderer.addEventListener('swipe', (e: Event) => swipes.push((e as CustomEvent).detail))
    reportSwipes(doc, renderer, new EdgeGesture(p => reports.push(p)))
    return { doc, swipes, reports }
  }
  it('a gesture from the strip is no swipe', () => {
    const { doc, swipes, reports } = setup()
    fire(doc, 'touchstart', 390, 300, 0)
    fire(doc, 'touchmove', 300, 300, 50)
    fire(doc, 'touchend', 100, 300, 300)
    expect(swipes).toEqual([])
    expect(reports).toEqual(['start', 'end'])
  })
  it('one from elsewhere is', () => {
    const { doc, swipes, reports } = setup()
    fire(doc, 'touchstart', 300, 300, 0)
    fire(doc, 'touchend', 100, 300, 100)
    expect(swipes.length).toBe(1)
    expect(reports).toEqual([])
  })
  it('a touchcancel sends the panel back', () => {
    const { doc, reports } = setup()
    fire(doc, 'touchstart', 390, 300, 0)
    fire(doc, 'touchmove', 300, 300, 50)
    fire(doc, 'touchcancel', 300, 300, 60)
    expect(reports).toEqual(['start', 'end'])
  })
})
