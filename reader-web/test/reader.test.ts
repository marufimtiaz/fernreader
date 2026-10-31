import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { FromWeb, ToWeb } from '../src/bridge/protocol'

// foliate-js is replaced by a fake <foliate-view>: these tests cover the Reader's open/close/warm-reopen logic only.
const h = vi.hoisted(() => ({ makeLoader: vi.fn(), epubInit: vi.fn() }))
vi.mock('../vendor/foliate-js/view.js', () => ({}))
vi.mock('../vendor/foliate-js/epub.js', () => ({ EPUB: class { init() { return h.epubInit() } } }))
vi.mock('../src/reader/loader', () => ({ makeLoader: h.makeLoader }))

import { Bridge } from '../src/bridge/bridge'
import { Reader } from '../src/reader/reader'

const tick = () => new Promise(r => setTimeout(r, 0)) // foliate moves asynchronously

class FakeView extends HTMLElement {
  static all: FakeView[] = []
  renderer: any = Object.assign(document.createElement('div'), { setStyles: vi.fn(), getContents: () => [] })
  lastLocation: any = null
  open = vi.fn(async () => {})
  static initHook: ((v: FakeView, lastLocation: string | null | undefined) => Promise<void>) | null = null
  init = vi.fn(async ({ lastLocation }: { lastLocation?: string | null }) => {
    if (FakeView.initHook) return FakeView.initHook(this, lastLocation)
    await tick(); this.relocateTo(lastLocation ?? 'cfi-start')
  })
  goTo = vi.fn(async (t: string): Promise<object | undefined> => { await tick(); this.relocateTo(t); return {} })
  close = vi.fn()
  getCFI = vi.fn((_index: number, range?: Range) => `cfi-hit:${range?.toString()}`)
  addAnnotation = vi.fn(async (_a: { value: string }) => ({}))
  deleteAnnotation = vi.fn(async (_a: { value: string }) => ({}))
  // Like foliate's paginator: a turn takes a while, and a turn asked for meanwhile is ignored (resolves at once).
  page = 0
  locked = false
  turnsMade: string[] = []
  next = vi.fn(() => this.turnBy(1))
  prev = vi.fn(() => this.turnBy(-1))
  async turnBy(d: number) {
    if (this.locked || !this.lastLocation) return
    this.locked = true
    await tick(); this.page += d; this.turnsMade.push(d > 0 ? 'next' : 'prev'); this.relocateTo(`p${this.page}`)
    await tick(); this.locked = false
  }
  constructor() {
    super(); FakeView.all.push(this)
    this.renderer.goTo = vi.fn(async ({ index }: { index: number }) => { await tick(); this.relocateTo(`start-of-${index}`) })
  }
  relocateTo(cfi: string) {
    this.lastLocation = { cfi, fraction: 0.25, section: { current: 1, total: 3 }, tocItem: { href: 'c1.xhtml' } }
    this.dispatchEvent(new CustomEvent('relocate', { detail: this.lastLocation }))
  }
}

const doc = (body: string) => async () => new DOMParser().parseFromString(`<html><body>${body}</body></html>`, 'text/html')
const book = () => ({ sections: [{ id: 'a' }, { id: 'b' }, { id: 'c', createDocument: doc('<p>Battle of Borodinó began</p>') }], toc: [], metadata: { title: 'T', identifier: 'id' }, resolveHref: () => ({ index: 0 }) })
const settle = () => new Promise(r => setTimeout(r, 40))

let sent: FromWeb[]
let bridge: Bridge
const send = (m: ToWeb) => bridge.receive(JSON.stringify(m))
const open = (sessionId: string, position: string | null = null) =>
  send({ v: 1, type: 'open', sessionId, position, texts: { missingPart: 'missing' } })
const types = () => sent.map(m => m.type)

beforeAll(() => {
  customElements.define('foliate-view', FakeView)
  globalThis.requestAnimationFrame = (cb: FrameRequestCallback) => setTimeout(() => cb(0), 0) as unknown as number
})
beforeEach(() => {
  FakeView.all = []; FakeView.initHook = null; document.body.innerHTML = ''
  h.makeLoader.mockReset().mockResolvedValue({ has: () => true })
  h.epubInit.mockReset().mockImplementation(async () => book())
  sent = []
  // Page counts are the counter's business (counter.test.ts); these tests follow the open/turn/seek sequence.
  bridge = new Bridge(json => { const m = JSON.parse(json); if (m.type !== 'pageCounts') sent.push(m) })
  new Reader(bridge)
})
afterEach(() => vi.restoreAllMocks())

describe('Reader open', () => {
  it('first open sends opened, relocate, painted', async () => {
    open('s1', 'cfi-a'); await settle(); await settle()
    expect(types()).toEqual(['opened', 'relocate', 'painted'])
    expect(sent[1]).toMatchObject({ sessionId: 's1', cfi: 'cfi-a', fraction: 0.25, sectionIndex: 1, tocHref: 'c1.xhtml' })
    expect(FakeView.all).toHaveLength(1)
  })
})

describe('warm reopen (P-016)', () => {
  it('reuses the laid-out view for the same session: no parse, no new view, same event sequence', async () => {
    open('s1', 'cfi-a'); await settle()
    const v = FakeView.all[0]
    v.relocateTo('cfi-b') // the reader turned pages
    send({ v: 1, type: 'close' }); await settle()
    expect(v.isConnected).toBe(true) // kept, hidden
    expect(v.close).not.toHaveBeenCalled()
    sent = []

    open('s1', 'cfi-b'); await settle()
    expect(h.makeLoader).toHaveBeenCalledTimes(1)
    expect(FakeView.all).toHaveLength(1)
    expect(v.open).toHaveBeenCalledTimes(1)
    expect(v.goTo).not.toHaveBeenCalled() // already there
    expect(v.style.visibility).toBe('')
    expect(types()).toEqual(['opened', 'relocate', 'painted'])
    expect(sent[1]).toMatchObject({ sessionId: 's1', cfi: 'cfi-b' })
  })

  it('moves the kept view when the requested position differs', async () => {
    open('s1', 'cfi-a'); await settle()
    const v = FakeView.all[0]
    send({ v: 1, type: 'close' }); sent = []
    open('s1', 'cfi-z'); await settle()
    expect(v.goTo).toHaveBeenCalledWith('cfi-z')
    expect(types()).toEqual(['opened', 'relocate', 'painted'])
    expect(sent[1]).toMatchObject({ cfi: 'cfi-z' })
  })

  it('stays where it was when the requested position no longer resolves', async () => {
    open('s1', 'cfi-a'); await settle()
    const v = FakeView.all[0]
    v.goTo.mockResolvedValueOnce(undefined) // foliate: logged and resolved undefined
    send({ v: 1, type: 'close' }); sent = []
    open('s1', 'cfi-gone'); await settle()
    expect(types()).toEqual(['opened', 'relocate', 'painted'])
    expect(sent[1]).toMatchObject({ cfi: 'cfi-a' })
  })

  it('hides the kept view and drops its events while closed', async () => {
    open('s1', 'cfi-a'); await settle()
    const v = FakeView.all[0]
    send({ v: 1, type: 'close' }); sent = []
    expect(v.style.visibility).toBe('hidden')
    v.relocateTo('cfi-q'); await settle()
    expect(sent).toEqual([])
  })

  it('opening another book destroys the kept view (one warm book)', async () => {
    open('s1', 'cfi-a'); await settle()
    const v = FakeView.all[0]
    send({ v: 1, type: 'close' })
    open('s2', null); await settle()
    expect(v.close).toHaveBeenCalled()
    expect(v.isConnected).toBe(false)
    expect(FakeView.all).toHaveLength(2)
    expect(h.makeLoader).toHaveBeenCalledTimes(2)
    expect(document.querySelectorAll('foliate-view')).toHaveLength(1)
  })

  it('a close during a warm reopen drops its events (stale token)', async () => {
    open('s1', 'cfi-a'); await settle()
    send({ v: 1, type: 'close' }); sent = []
    open('s1', 'cfi-z'); send({ v: 1, type: 'close' }); await settle()
    expect(types()).not.toContain('relocate')
    expect(types()).not.toContain('painted')
  })
})

describe('page turns (F-030)', () => {
  const turned = () => sent.filter(m => m.type === 'relocate').map(m => (m as { cfi: string }).cfi)

  it('three quick nexts turn three pages, in order', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]; sent = []
    send({ v: 1, type: 'next' }); send({ v: 1, type: 'next' }); send({ v: 1, type: 'next' }); await settle()
    expect(v.turnsMade).toEqual(['next', 'next', 'next'])
    expect(turned()).toEqual(['p1', 'p2', 'p3'])
  })

  it('a next/prev mix keeps its order', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    send({ v: 1, type: 'next' }); send({ v: 1, type: 'next' }); send({ v: 1, type: 'prev' }); await settle()
    expect(v.turnsMade).toEqual(['next', 'next', 'prev'])
    expect(v.page).toBe(1)
  })

  it('turns sent while the book is still opening are applied after it settles', async () => {
    let release!: () => void
    h.epubInit.mockImplementation(() => new Promise(r => { release = () => r(book()) }))
    open('s1', 'cfi-a')
    send({ v: 1, type: 'next' }); send({ v: 1, type: 'next' }) // no view yet
    await settle()
    release(); await settle()
    const v = FakeView.all[0]
    expect(v.turnsMade).toEqual(['next', 'next'])
    expect(turned()).toEqual(['cfi-a', 'p1', 'p2']) // the seek first, then the turns
  })

  it('keeps at most three pending turns', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    for (let i = 0; i < 6; i++) send({ v: 1, type: 'next' })
    await settle()
    expect(v.turnsMade).toHaveLength(3)
  })

  it('close drops pending turns', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    send({ v: 1, type: 'next' }); send({ v: 1, type: 'next' }); send({ v: 1, type: 'close' }); await settle()
    expect(v.turnsMade.length).toBeLessThanOrEqual(1) // the one already running may finish
    sent = []
    open('s1', v.lastLocation.cfi); await settle()
    expect(v.turnsMade.length).toBeLessThanOrEqual(1)
    expect(types()).toEqual(['opened', 'relocate', 'painted'])
  })
})

describe('initial seek (I1) and goTo during open (C1, F-037)', () => {
  const relocates = () => sent.filter(m => m.type === 'relocate').map(m => (m as { cfi: string }).cfi)

  it('relocates foliate emits while seeking to the saved position are not sent; the landed one is', async () => {
    FakeView.initHook = async (v, lastLocation) => { v.relocateTo('cfi-chapter-start'); await tick(); v.relocateTo(lastLocation ?? 'cfi-start') }
    open('s1', 'cfi-saved'); await settle()
    expect(relocates()).toEqual(['cfi-saved'])
    expect(types()).toEqual(['opened', 'relocate', 'painted'])
  })

  it('a goTo that arrives while the book is still opening is applied after the open settles', async () => {
    let release!: () => void
    h.epubInit.mockImplementation(() => new Promise(r => { release = () => r(book()) }))
    open('s1', null)
    send({ v: 1, type: 'goTo', target: 'cfi-saved', id: 1 }) // no view yet
    await settle()
    release(); await settle()
    const v = FakeView.all[0]
    expect(v.goTo).toHaveBeenCalledWith('cfi-saved')
    expect(relocates()).toEqual(['cfi-start', 'cfi-saved'])
    const noPaint = sent.filter(m => m.type !== 'painted')
    expect(noPaint.at(-2)).toMatchObject({ type: 'relocate', cfi: 'cfi-saved' })
    expect(noPaint.at(-1)).toEqual({ v: 1, type: 'seeked', sessionId: 's1', id: 1 })
  })

  it('a goTo during the first layout (view.init running) waits for it', async () => {
    let releaseInit!: () => void
    const order: string[] = []
    FakeView.initHook = v => new Promise<void>(r => { releaseInit = () => { order.push('init'); v.relocateTo('cfi-start'); r() } })
    open('s1', null); await settle()
    const v = FakeView.all[0]
    v.goTo.mockImplementation(async (t: string) => { order.push('goTo'); v.relocateTo(t); return {} })
    send({ v: 1, type: 'goTo', target: 'cfi-saved', id: 2 }); await settle()
    expect(order).toEqual([])
    releaseInit(); await settle()
    expect(order).toEqual(['init', 'goTo'])
    expect(v.lastLocation.cfi).toBe('cfi-saved')
    expect(sent.filter(m => m.type !== 'painted').at(-1)).toEqual({ v: 1, type: 'seeked', sessionId: 's1', id: 2 })
  })

  it('a goTo without id (TOC jump) sends one relocate and no seeked', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    v.goTo.mockImplementation(async (t: string) => { v.relocateTo('cfi-section-start'); await tick(); v.relocateTo(t); return {} })
    sent = []
    send({ v: 1, type: 'goTo', target: 'c2.xhtml' }); await settle()
    expect(relocates()).toEqual(['c2.xhtml'])
    expect(types()).not.toContain('seeked')
  })

  it('a goTo that does not resolve still answers seeked (from where the view is)', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    v.goTo.mockResolvedValueOnce(undefined)
    sent = []
    send({ v: 1, type: 'goTo', target: 'cfi-gone', id: 3 }); await settle()
    expect(relocates()).toEqual(['cfi-start'])
    expect(sent.filter(m => m.type !== 'painted').at(-1)).toEqual({ v: 1, type: 'seeked', sessionId: 's1', id: 3 })
  })

  it('a goTo queued for a book that was closed is dropped', async () => {
    let release!: () => void
    h.epubInit.mockImplementation(() => new Promise(r => { release = () => r(book()) }))
    open('s1', null)
    send({ v: 1, type: 'goTo', target: 'cfi-saved', id: 1 })
    await settle()
    send({ v: 1, type: 'close' })
    release(); await settle()
    expect(types()).not.toContain('seeked')
  })
})

describe('stale events', () => {
  it('a throwing goTo on warm reopen stays at the kept location', async () => {
    open('s1', 'cfi-a'); await settle()
    const v = FakeView.all[0]
    v.goTo.mockRejectedValueOnce(new Error('bad target'))
    send({ v: 1, type: 'close' }); sent = []
    vi.spyOn(console, 'warn').mockImplementation(() => {}) // printing the error can outlast settle()
    open('s1', 'cfi-bad'); await settle()
    expect(types()).toEqual(['opened', 'relocate', 'painted'])
    expect(sent[1]).toMatchObject({ cfi: 'cfi-a' })
  })

  it('painted is dropped when a close comes between relocate and paint', async () => {
    open('s1', 'cfi-a'); await settle()
    const v = FakeView.all[0]
    send({ v: 1, type: 'close' })
    open('s1', 'cfi-a') // reopen sends relocate synchronously, paint is 2 frames later
    send({ v: 1, type: 'close' }); sent = []
    await settle()
    expect(types()).not.toContain('painted')
    expect(v.isConnected).toBe(true)
  })
})

describe('goToText (F-050)', () => {
  const relocates = () => sent.filter(m => m.type === 'relocate').map(m => (m as { cfi: string }).cfi)
  const goToText = (needle: string, offset = 10) => send({ v: 1, type: 'goToText', sectionIndex: 2, offset, length: needle.length, needle })
  const probe = () => (window as any).__readerTest.searchHighlight()

  it('a hit goes to its range with one relocate and gets the temporary highlight', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]; sent = []
    goToText('borodino', 11); await settle()
    expect(v.getCFI.mock.calls[0][0]).toBe(2)
    expect(v.goTo).toHaveBeenCalledWith('cfi-hit:Borodinó')
    expect(relocates()).toEqual(['cfi-hit:Borodinó'])
    expect(v.addAnnotation).toHaveBeenCalledWith({ value: 'cfi-hit:Borodinó' })
    expect(probe()).toBe('cfi-hit:Borodinó')
    expect(sent.some(m => m.type === 'error')).toBe(false)
  })

  it('an unresolvable needle goes to the section start and reports hit_not_found', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]; sent = []
    goToText('zygote'); await settle()
    expect(v.renderer.goTo).toHaveBeenCalledWith({ index: 2, anchor: 0 })
    expect(v.goTo).not.toHaveBeenCalled()
    expect(v.addAnnotation).not.toHaveBeenCalled()
    expect(types()).toEqual(['relocate', 'error', 'painted'])
    expect(sent[1]).toMatchObject({ sessionId: 's1', kind: 'hit_not_found' })
    expect(probe()).toBeNull()
  })

  it('a jump that throws is a hit not found: section start, no highlight, hit_not_found', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]; sent = []
    v.goTo.mockRejectedValueOnce(new Error('boom'))
    goToText('borodino', 11); await settle()
    expect(v.renderer.goTo).toHaveBeenCalledWith({ index: 2, anchor: 0 })
    expect(v.addAnnotation).not.toHaveBeenCalled()
    expect(probe()).toBeNull()
    expect(sent.some(m => m.type === 'error' && (m as any).kind === 'hit_not_found')).toBe(true)
  })

  it('a section without a document is not found too', async () => {
    open('s1', null); await settle()
    sent = []
    send({ v: 1, type: 'goToText', sectionIndex: 1, offset: 0, length: 1, needle: 'a' }); await settle()
    expect(types()).toEqual(['relocate', 'error', 'painted'])
  })

  it('only one search highlight: a new hit and clearSearchHighlight remove the previous one', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    goToText('borodino', 11); await settle()
    goToText('battle', 0); await settle()
    expect(v.deleteAnnotation).toHaveBeenCalledWith({ value: 'cfi-hit:Borodinó' })
    expect(probe()).toBe('cfi-hit:Battle')
    send({ v: 1, type: 'clearSearchHighlight' }); await settle()
    expect(v.deleteAnnotation).toHaveBeenLastCalledWith({ value: 'cfi-hit:Battle' })
    expect(probe()).toBeNull()
  })

  it('draws the highlight again when its section gets an overlayer again, and only then', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    goToText('borodino', 11); await settle()
    v.addAnnotation.mockClear()
    v.dispatchEvent(new CustomEvent('create-overlay', { detail: { index: 1 } }))
    expect(v.addAnnotation).not.toHaveBeenCalled()
    v.dispatchEvent(new CustomEvent('create-overlay', { detail: { index: 2 } }))
    expect(v.addAnnotation).toHaveBeenCalledWith({ value: 'cfi-hit:Borodinó' })
  })

  it('draws only the search annotation, in the search colour', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    goToText('borodino', 11); await settle()
    const draw = vi.fn()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 'other' } } }))
    expect(draw).not.toHaveBeenCalled()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 'cfi-hit:Borodinó' } } }))
    expect(draw).toHaveBeenCalledTimes(1)
    expect(draw.mock.calls[0][0]).toBeTypeOf('function')
    expect(draw.mock.calls[0][1]).toEqual({ color: '#ff9800' }) // opaque: foliate applies its own 0.3 opacity
  })

  it('a hit whose CFI does not resolve is treated as not found', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]; sent = []
    v.getCFI.mockReturnValue('epubcfi(/6/2!/4/2)')
    ;(v as any).book = { resolveCFI: () => null, sections: [{}, {}, {}] }
    goToText('borodino', 11); await settle()
    expect(v.renderer.goTo).toHaveBeenCalledWith({ index: 2, anchor: 0 })
    expect(types()).toEqual(['relocate', 'error', 'painted'])
    expect(v.addAnnotation).not.toHaveBeenCalled()
    expect(probe()).toBeNull()
  })

  it('close removes the highlight', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    goToText('borodino', 11); await settle()
    send({ v: 1, type: 'close' })
    expect(v.deleteAnnotation).toHaveBeenCalledWith({ value: 'cfi-hit:Borodinó' })
  })
})

describe('swipes (F-032, C-026)', () => {
  const swipe = (v: FakeView, dx: number, dy = 0, dt = 150) => v.renderer.dispatchEvent(new CustomEvent('swipe', { detail: { dx, dy, dt } }))
  const DEFAULTS = { fontScale: 1, fontFamily: 'publisher', lineHeight: 1.5, marginPercent: 5, align: 'publisher', theme: 'light', flow: 'paginated' } as const

  it('the paginator hands its swipes to the host (no native drag turn)', async () => {
    open('s1', null); await settle()
    expect(FakeView.all[0].renderer.hasAttribute('host-swipe')).toBe(true)
  })

  it('one swipe toward the left is one next', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    swipe(v, -200); await settle()
    expect(v.turnsMade).toEqual(['next'])
    swipe(v, 200); await settle()
    expect(v.turnsMade).toEqual(['next', 'prev'])
  })

  it('a second and third swipe during a running turn are queued, not lost', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    swipe(v, -200); swipe(v, -200); swipe(v, -200); await settle()
    expect(v.turnsMade).toEqual(['next', 'next', 'next'])
  })

  it('swipes share the bridge turns cap', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    for (let i = 0; i < 6; i++) swipe(v, -200)
    await settle()
    expect(v.turnsMade).toHaveLength(3)
  })

  it('a swipe before the first layout settled waits for it', async () => {
    let releaseInit!: () => void
    FakeView.initHook = (v, loc) => new Promise<void>(r => { releaseInit = () => { v.relocateTo(loc ?? 'cfi-start'); r() } })
    open('s1', null); await settle()
    const v = FakeView.all[0]
    swipe(v, -200); await settle()
    expect(v.turnsMade).toEqual([])
    releaseInit(); await settle()
    expect(v.turnsMade).toEqual(['next'])
  })

  it('a tap-sized movement or a vertical swipe turns nothing', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    swipe(v, -5, 2); swipe(v, 0, -200); await settle()
    expect(v.turnsMade).toEqual([])
  })

  it('auto follows an rtl book: finger moving right is next', async () => {
    h.epubInit.mockImplementation(async () => ({ ...book(), dir: 'rtl' }))
    open('s1', null); await settle()
    const v = FakeView.all[0]
    swipe(v, 200); await settle()
    expect(v.turnsMade).toEqual(['next'])
  })

  it('a forced direction applies from open and changes live', async () => {
    h.epubInit.mockImplementation(async () => ({ ...book(), dir: 'rtl' }))
    send({ v: 1, type: 'open', sessionId: 's1', position: null, texts: { missingPart: 'm' }, settings: { ...DEFAULTS, swipeDirection: 'ltr', pageAnimation: 'slide' } })
    await settle()
    const v = FakeView.all[0]
    swipe(v, -200); await settle()
    expect(v.turnsMade).toEqual(['next'])
    send({ v: 1, type: 'setSettings', settings: { ...DEFAULTS, swipeDirection: 'rtl', pageAnimation: 'slide' } })
    swipe(v, -200); await settle()
    expect(v.turnsMade).toEqual(['next', 'prev'])
  })

  it('swipes of a closed book are dropped', async () => {
    open('s1', null); await settle()
    const v = FakeView.all[0]
    send({ v: 1, type: 'close' })
    swipe(v, -200); await settle()
    expect(v.turnsMade).toEqual([])
  })
})

describe('page animation (F-032)', () => {
  const DEFAULTS = { fontScale: 1, fontFamily: 'publisher', lineHeight: 1.5, marginPercent: 5, align: 'publisher', theme: 'light', flow: 'paginated' } as const

  it('slide by default, none from open, switched live', async () => {
    open('s1', null); await settle()
    const r = FakeView.all[0].renderer
    expect(r.hasAttribute('animated')).toBe(true)
    send({ v: 1, type: 'setSettings', settings: { ...DEFAULTS, pageAnimation: 'none' } }); await settle()
    expect(r.hasAttribute('animated')).toBe(false)
    send({ v: 1, type: 'setSettings', settings: { ...DEFAULTS, pageAnimation: 'slide' } }); await settle()
    expect(r.hasAttribute('animated')).toBe(true)
  })

  it('none from the open message', async () => {
    send({ v: 1, type: 'open', sessionId: 's1', position: null, texts: { missingPart: 'm' }, settings: { ...DEFAULTS, pageAnimation: 'none' } })
    await settle()
    expect(FakeView.all[0].renderer.hasAttribute('animated')).toBe(false)
  })
})
