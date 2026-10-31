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
    const { Overlayer } = await import('../vendor/foliate-js/overlayer.js')
    // An outline, so a hit never looks like a user highlight (the user's orange is a fill), on every theme.
    expect(draw.mock.calls[0]).toEqual([Overlayer.outline, { color: '#ff9800', width: 2, radius: 3 }])
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

describe('links, history, footnotes (F-042, F-043, F-044)', () => {
  const OPS = 'http://www.idpf.org/2007/ops'
  const xhtml = (body: string) => new DOMParser().parseFromString(
    `<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="${OPS}"><head><title>t</title></head><body>${body}</body></html>`,
    'application/xhtml+xml')
  const NOTES = `<h1>Notes</h1><aside epub:type="footnote" id="n5"><p><a href="ch1.xhtml#r5">5.</a> Footnote number 5 text. <a href="https://www.gutenberg.org/">site</a></p></aside>`
  const CH1 = `<p>Text<a epub:type="noteref" href="notes.xhtml#n5" id="r5">5</a>. See <a href="ch2.xhtml#target">the target</a>.</p>`
  /** Spine: 0 ch1, 1 ch2, 2 notes; hrefs resolve like foliate's (book paths, `#id` anchors). */
  const linkBook = () => ({
    ...book(),
    sections: [
      { id: 'c1', createDocument: async () => xhtml(CH1), resolveHref: (h: string) => (/^\w+:/.test(h) ? h : `OEBPS/${h}`) },
      { id: 'c2', createDocument: async () => xhtml('<p id="target">Target</p>'), resolveHref: (h: string) => `OEBPS/${h}` },
      { id: 'n', createDocument: async () => xhtml(NOTES), resolveHref: (h: string) => (/^\w+:/.test(h) ? h : `OEBPS/${h}`) },
    ],
    resolveHref: (href: string) => {
      const [path, hash] = href.split('#')
      const index = ['OEBPS/ch1.xhtml', 'OEBPS/ch2.xhtml', 'OEBPS/notes.xhtml'].indexOf(path)
      if (index < 0) return null
      return { index, anchor: hash ? (d: Document) => d.getElementById(hash) : () => 0 }
    },
  })
  const ch1 = xhtml(CH1)
  const link = (v: FakeView, a: Element, href: string) => {
    const e = new CustomEvent('link', { detail: { a, href }, cancelable: true })
    v.dispatchEvent(e)
    return e
  }
  const external = (v: FakeView, href_: string) => {
    const e = new CustomEvent('external-link', { detail: { a: null, href_ }, cancelable: true })
    v.dispatchEvent(e)
    return e
  }
  const without = (t: string) => sent.filter(m => m.type !== t && m.type !== 'painted')
  const popup = () => document.getElementById('er-footnote')
  const panel = () => popup()?.querySelector('[role="dialog"]') as HTMLElement | null
  const opened = async () => { h.epubInit.mockImplementation(async () => linkBook()); open('s1', 'cfi-origin'); await settle(); sent = [] }

  it('a followed link pushes the exact origin, goes there through foliate, and says history depth 1', async () => {
    await opened()
    const v = FakeView.all[0]
    const e = link(v, ch1.querySelectorAll('a')[1], 'OEBPS/ch2.xhtml#target'); await settle()
    expect(e.defaultPrevented).toBe(true) // foliate's own navigation is not used
    expect(without('relocate')).toEqual([{ v: 1, type: 'history', sessionId: 's1', depth: 1 }])
    expect(v.goTo).toHaveBeenCalledWith('OEBPS/ch2.xhtml#target')
    expect(sent.filter(m => m.type === 'relocate').map(m => (m as { cfi: string }).cfi)).toEqual(['OEBPS/ch2.xhtml#target'])
  })

  it('back returns to the origin location and walks the history; an empty history moves nothing', async () => {
    await opened()
    const v = FakeView.all[0]
    link(v, ch1.querySelectorAll('a')[1], 'OEBPS/ch2.xhtml#target'); await settle()
    v.relocateTo('cfi-later') // a page turn after the jump
    link(v, ch1.querySelectorAll('a')[1], 'OEBPS/ch2.xhtml#target'); await settle()
    sent = []
    send({ v: 1, type: 'back' }); await settle()
    expect(sent.filter(m => m.type !== 'painted')).toEqual([
      { v: 1, type: 'history', sessionId: 's1', depth: 1 },
      expect.objectContaining({ type: 'relocate', cfi: 'cfi-later' }),
    ])
    sent = []
    send({ v: 1, type: 'back' }); await settle()
    expect(sent.filter(m => m.type !== 'painted').map(m => (m as any).cfi ?? (m as any).depth)).toEqual([0, 'cfi-origin'])
    sent = []; v.goTo.mockClear()
    send({ v: 1, type: 'back' }); await settle()
    expect(without('x')).toEqual([{ v: 1, type: 'history', sessionId: 's1', depth: 0 }])
    expect(v.goTo).not.toHaveBeenCalled()
  })

  it('TOC jumps do not create history entries, and a new open starts with none', async () => {
    await opened()
    const v = FakeView.all[0]
    send({ v: 1, type: 'goTo', target: 'c2.xhtml' }); await settle()
    expect(types()).not.toContain('history')
    link(v, ch1.querySelectorAll('a')[1], 'OEBPS/ch2.xhtml#target'); await settle()
    send({ v: 1, type: 'close' }); open('s2', null); await settle(); sent = []
    send({ v: 1, type: 'back' }); await settle()
    expect(without('x')).toEqual([{ v: 1, type: 'history', sessionId: 's2', depth: 0 }])
  })

  it('a note reference opens the popup with the note text instead of navigating', async () => {
    await opened()
    const v = FakeView.all[0]
    v.goTo.mockClear()
    const e = link(v, ch1.querySelector('a')!, 'OEBPS/notes.xhtml#n5'); await settle()
    expect(e.defaultPrevented).toBe(true)
    expect(without('x')).toEqual([{ v: 1, type: 'footnote', sessionId: 's1', open: true }])
    expect(panel()?.textContent).toContain('Footnote number 5 text.')
    expect(panel()?.querySelector('script, [onclick], [href]')).toBeNull()
    expect(v.goTo).not.toHaveBeenCalled()
    expect((window as any).__readerTest.footnote()).toContain('Footnote number 5')
  })

  it('a plain link to an element marked as a note in a loaded section opens the popup too', async () => {
    await opened()
    const v = FakeView.all[0]
    v.renderer.getContents = () => [{ index: 0, doc: xhtml(CH1) }, { index: 2, doc: xhtml(NOTES) }]
    const a = xhtml('<p><a href="notes.xhtml#n5">see note</a></p>').querySelector('a')!
    link(v, a, 'OEBPS/notes.xhtml#n5'); await settle()
    expect(panel()?.textContent).toContain('Footnote number 5')
  })

  it('a tap outside the popup closes it and tells the app; a tap inside does not', async () => {
    await opened()
    link(FakeView.all[0], ch1.querySelector('a')!, 'OEBPS/notes.xhtml#n5'); await settle(); sent = []
    panel()!.querySelector('p')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(popup()).not.toBeNull()
    popup()!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(popup()).toBeNull()
    expect(without('x')).toEqual([{ v: 1, type: 'footnote', sessionId: 's1', open: false }])
  })

  it('closeFootnote closes it (Back in the app)', async () => {
    await opened()
    link(FakeView.all[0], ch1.querySelector('a')!, 'OEBPS/notes.xhtml#n5'); await settle(); sent = []
    send({ v: 1, type: 'closeFootnote' }); await settle()
    expect(popup()).toBeNull()
    expect(without('x')).toEqual([{ v: 1, type: 'footnote', sessionId: 's1', open: false }])
  })

  it('an internal link in the note closes the popup and navigates with history; an external one asks the app', async () => {
    await opened()
    const v = FakeView.all[0]
    link(v, ch1.querySelector('a')!, 'OEBPS/notes.xhtml#n5'); await settle(); sent = []
    const [back, site] = [...panel()!.querySelectorAll('a')]
    site.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    expect(without('x')).toEqual([{ v: 1, type: 'externalLink', sessionId: 's1', href: 'https://www.gutenberg.org/' }])
    expect(popup()).not.toBeNull()
    sent = []
    back.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); await settle()
    expect(popup()).toBeNull()
    expect(without('relocate')).toEqual([
      { v: 1, type: 'footnote', sessionId: 's1', open: false },
      { v: 1, type: 'history', sessionId: 's1', depth: 1 },
    ])
    expect(v.goTo).toHaveBeenCalledWith('OEBPS/ch1.xhtml#r5')
  })

  it('external links go to the app; unsafe schemes are dropped; the view never opens a window', async () => {
    await opened()
    const v = FakeView.all[0]
    const e = external(v, 'https://www.gutenberg.org/')
    expect(e.defaultPrevented).toBe(true)
    for (const bad of ['javascript:alert(1)', 'file:///etc/hosts', 'content://x', 'intent://x#Intent;end']) expect(external(v, bad).defaultPrevented).toBe(true)
    await settle()
    expect(without('x')).toEqual([{ v: 1, type: 'externalLink', sessionId: 's1', href: 'https://www.gutenberg.org/' }])
  })

  it('close removes an open popup', async () => {
    await opened()
    link(FakeView.all[0], ch1.querySelector('a')!, 'OEBPS/notes.xhtml#n5'); await settle()
    send({ v: 1, type: 'close' })
    expect(popup()).toBeNull()
  })
})

describe('bookmarks on the visible page (F-041)', () => {
  const PAGE = 'epubcfi(/6/4!/4,/2/1:0,/6/1:5)'
  const HERE = 'epubcfi(/6/4!/4/2/1:10)'
  const ELSEWHERE = 'epubcfi(/6/8!/4/6/1:0)'
  const setBookmarks = (cfis: string[]) => send({ v: 1, type: 'setBookmarks', sessionId: 's1', cfis })

  it('answers setBookmarks with the ones on the page now showing, and puts them on every later relocate', async () => {
    open('s1'); await settle(); FakeView.all[0].relocateTo(PAGE); await settle()
    sent = []
    setBookmarks([HERE, ELSEWHERE]); await settle()
    expect(sent).toEqual([{ v: 1, type: 'pageBookmarks', sessionId: 's1', cfis: [HERE] }])
    sent = []
    FakeView.all[0].relocateTo(PAGE); await settle()
    expect(sent.find(m => m.type === 'relocate')).toMatchObject({ bookmarks: [HERE] })
    sent = []
    FakeView.all[0].relocateTo('epubcfi(/6/6!/4/2/1:0)'); await settle() // another page
    const r = sent.find(m => m.type === 'relocate') as Record<string, unknown>
    expect(r.bookmarks).toBeUndefined()
  })

  it('an empty list clears the marks; a stale session id is ignored; a new open forgets the list', async () => {
    open('s1'); await settle(); FakeView.all[0].relocateTo(PAGE); await settle()
    setBookmarks([HERE]); await settle(); sent = []
    setBookmarks([]); await settle()
    expect(sent).toEqual([{ v: 1, type: 'pageBookmarks', sessionId: 's1', cfis: [] }])
    sent = []
    send({ v: 1, type: 'setBookmarks', sessionId: 'other', cfis: [HERE] }); await settle()
    expect(sent).toEqual([])
    setBookmarks([HERE]); await settle()
    send({ v: 1, type: 'close' }); open('s1', PAGE); await settle(); FakeView.all[0].relocateTo(PAGE); await settle()
    const rel = sent.filter(m => m.type === 'relocate').pop() as Record<string, unknown>
    expect(rel.bookmarks).toBeUndefined()
  })
})

describe('selection and annotations (F-060)', () => {
  /** A section document with a browsing context (Selection works), loaded into the view like foliate does. */
  const section = (v: FakeView, index: number, body: string) => {
    const f = document.createElement('iframe'); document.body.append(f)
    const d = f.contentDocument!; d.body.innerHTML = body
    const prev = v.renderer.getContents
    const list = [...(prev?.() ?? []), { index, doc: d }]
    v.renderer.getContents = () => list
    v.dispatchEvent(new CustomEvent('load', { detail: { doc: d, index } }))
    return d
  }
  const selectIn = (d: Document, start: number, end: number) => {
    const t = d.querySelector('p')!.firstChild!
    const r = d.createRange(); r.setStart(t, start); r.setEnd(t, end)
    d.getSelection()!.removeAllRanges(); d.getSelection()!.addRange(r)
    d.dispatchEvent(new Event('selectionchange'))
  }
  const wait = (ms: number) => new Promise(r => setTimeout(r, ms))
  /** What a draw-annotation call draws: the annotation id it is tagged with, fill (highlight) or underline, options. */
  const look = ([f, o]: any[]) => {
    const g = f([{ left: 0, top: 0, right: 10, bottom: 10, width: 10, height: 10 }], o) as SVGElement
    return [g.getAttribute('data-er-id'), g.style.opacity ? 'highlight' : 'underline', o]
  }
  const ofType = <T extends FromWeb['type']>(t: T) => sent.filter(m => m.type === t) as Extract<FromWeb, { type: T }>[]
  const opened = async () => { open('s1', null); await settle(); sent = []; return FakeView.all[0] }

  it('a settled selection is sent with its exact text, range CFI and section; a collapse sends cleared', async () => {
    const v = await opened()
    const d = section(v, 2, '<p>The quick brown fox</p>')
    selectIn(d, 4, 10)
    expect(ofType('selection')).toEqual([]) // debounced
    await wait(200)
    expect(ofType('selection')).toEqual([{ v: 1, type: 'selection', sessionId: 's1', text: 'quick ', cfi: 'cfi-hit:quick ', sectionIndex: 2 }])
    d.getSelection()!.removeAllRanges(); d.dispatchEvent(new Event('selectionchange'))
    await wait(200)
    expect(ofType('selection')[1]).toEqual({ v: 1, type: 'selection', sessionId: 's1', text: '', cfi: null, sectionIndex: -1 })
  })

  it('clearSelection removes the selection in every loaded section and reports it cleared', async () => {
    const v = await opened()
    const d = section(v, 1, '<p>Some words here</p>')
    selectIn(d, 0, 4); await wait(200)
    send({ v: 1, type: 'clearSelection' })
    expect(d.getSelection()!.isCollapsed).toBe(true)
    await wait(200)
    expect(ofType('selection').map(m => m.cfi)).toEqual(['cfi-hit:Some', null])
  })

  it('a tap that dismisses a selection is not a tap for the app (no controls, no turn)', async () => {
    const v = await opened()
    const d = section(v, 1, '<p>Some words here</p>')
    selectIn(d, 0, 4)
    d.dispatchEvent(new Event('pointerdown'))
    d.getSelection()!.removeAllRanges() // the browser collapses it before the click arrives
    d.body.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 10, clientY: 10 }))
    expect(ofType('tap')).toEqual([])
    d.dispatchEvent(new Event('pointerdown'))
    d.body.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 10, clientY: 10 }))
    expect(ofType('tap').length).toBe(1) // the next tap, without a selection, is one
  })

  it('addAnnotation draws a highlight or an underline in its colour; other sessions are ignored', async () => {
    const { Overlayer } = await import('../vendor/foliate-js/overlayer.js')
    const v = await opened()
    send({ v: 1, type: 'addAnnotation', sessionId: 'other', id: 'x', cfi: 'cfi-x', color: 'green', style: 'highlight' })
    expect(v.addAnnotation).not.toHaveBeenCalled()
    send({ v: 1, type: 'addAnnotation', sessionId: 's1', id: 'a1', cfi: 'cfi-a', color: 'green', style: 'highlight' })
    send({ v: 1, type: 'addAnnotation', sessionId: 's1', id: 'a2', cfi: 'cfi-b', color: 'blue', style: 'underline' })
    await settle()
    expect(v.addAnnotation).toHaveBeenCalledWith({ value: 'cfi-a' })
    expect(v.addAnnotation).toHaveBeenCalledWith({ value: 'cfi-b' })
    const draw = vi.fn()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 'cfi-a' } } }))
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 'cfi-b' } } }))
    expect(draw.mock.calls.map(look)).toEqual([['a1', 'highlight', { color: '#66bb6a' }], ['a2', 'underline', { color: '#42a5f5', width: 2 }]])
    expect(draw.mock.calls[0][0]).not.toBe(Overlayer.highlight) // tagged with the id (test probes find its drawing)
  })

  it('the same id again changes the drawing; annotations are drawn again on a new overlayer and dropped on close', async () => {
    const v = await opened()
    v.addAnnotation.mockImplementation(async () => ({ index: 3 }))
    send({ v: 1, type: 'addAnnotation', sessionId: 's1', id: 'a1', cfi: 'cfi-a', color: 'green', style: 'highlight' }); await settle()
    send({ v: 1, type: 'addAnnotation', sessionId: 's1', id: 'a1', cfi: 'cfi-a', color: 'pink', style: 'highlight' }); await settle()
    const draw = vi.fn()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 'cfi-a' } } }))
    expect(draw.mock.calls[0][1]).toEqual({ color: '#f06292' })
    v.addAnnotation.mockClear()
    v.dispatchEvent(new CustomEvent('create-overlay', { detail: { index: 1 } }))
    expect(v.addAnnotation).not.toHaveBeenCalled()
    v.dispatchEvent(new CustomEvent('create-overlay', { detail: { index: 3 } }))
    expect(v.addAnnotation).toHaveBeenCalledWith({ value: 'cfi-a' })
    send({ v: 1, type: 'close' })
    open('s1', null); await settle()
    draw.mockClear()
    FakeView.all[0].dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 'cfi-a' } } }))
    expect(draw).not.toHaveBeenCalled()
  })
  // F-061: the book's annotations, drawn lazily per section; test CFIs "s<index>:<rest>" resolve to that section.
  const annotated = async () => {
    h.epubInit.mockImplementation(async () => ({ ...book(), resolveCFI: (c: string) => ({ index: Number(/^s(\d+):/.exec(c)?.[1] ?? -1) }) }))
    return opened()
  }
  const items = (...l: [string, string, string?, ('highlight' | 'underline')?][]) =>
    l.map(([id, cfi, color = 'yellow', style = 'highlight']) => ({ id, cfi, color, style }))
  const setAnnotations = (sessionId: string, l: ReturnType<typeof items>) => send({ v: 1, type: 'setAnnotations', sessionId, items: l })
  const values = (f: ReturnType<typeof vi.fn>) => f.mock.calls.map(c => (c[0] as { value: string }).value)
  const fakeRange = (d: Document, rects: { left: number; top: number; right: number; bottom: number }[]) =>
    ({ startContainer: d.body, getClientRects: () => rects }) as unknown as Range

  it('setAnnotations draws the loaded sections now and the others when their overlayer is made; other sessions are ignored', async () => {
    const v = await annotated()
    section(v, 1, '<p>One</p>')
    setAnnotations('other', items(['x', 's1:x']))
    setAnnotations('s1', items(['a', 's1:a', 'green'], ['b', 's2:b', 'blue', 'underline']))
    await settle()
    expect(values(v.addAnnotation)).toEqual(['s1:a']) // section 2 is not loaded: nothing resolved or drawn for it yet
    v.dispatchEvent(new CustomEvent('create-overlay', { detail: { index: 2 } }))
    expect(values(v.addAnnotation)).toEqual(['s1:a', 's2:b'])
    const { Overlayer } = await import('../vendor/foliate-js/overlayer.js')
    const draw = vi.fn()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 's2:b' } } }))
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 's1:x' } } }))
    expect(draw.mock.calls.map(look)).toEqual([['b', 'underline', { color: '#42a5f5', width: 2 }]])
  })

  it('setAnnotations again changes only what changed: recoloured drawn again, removed erased, unchanged left alone', async () => {
    const v = await annotated()
    section(v, 1, '<p>One</p>')
    setAnnotations('s1', items(['a', 's1:a'], ['b', 's1:b'], ['c', 's1:c'])); await settle()
    v.addAnnotation.mockClear()
    setAnnotations('s1', items(['a', 's1:a', 'pink'], ['c', 's1:c'])); await settle()
    expect(values(v.addAnnotation)).toEqual(['s1:a'])
    expect(values(v.deleteAnnotation)).toEqual(['s1:b'])
    const draw = vi.fn()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 's1:a' } } }))
    expect(draw.mock.calls[0][1]).toEqual({ color: '#f06292' })
  })

  it('removeAnnotation erases it from foliate before anything else is drawn, and it is never drawn again', async () => {
    const v = await annotated()
    section(v, 1, '<p>One</p>')
    setAnnotations('s1', items(['a', 's1:a'], ['b', 's1:b'])); await settle()
    send({ v: 1, type: 'removeAnnotation', sessionId: 'other', id: 'a' })
    send({ v: 1, type: 'removeAnnotation', sessionId: 's1', id: 'a' }); await settle()
    expect(values(v.deleteAnnotation)).toEqual(['s1:a'])
    v.addAnnotation.mockClear()
    v.dispatchEvent(new CustomEvent('create-overlay', { detail: { index: 1 } }))
    expect(values(v.addAnnotation)).toEqual(['s1:b'])
    const draw = vi.fn()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 's1:a' } } }))
    expect(draw).not.toHaveBeenCalled()
  })

  it('a tap on an annotation is annotationTapped, not a tap; elsewhere it is a tap; a link inside it stays a link', async () => {
    const v = await annotated()
    const d = section(v, 1, '<p>Some <a href="#n">note</a> words</p>')
    setAnnotations('s1', items(['a', 's1:a'], ['b', 's1:b'])); await settle()
    const draw = vi.fn()
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 's1:a' }, doc: d, range: fakeRange(d, [{ left: 0, top: 0, right: 100, bottom: 20 }]) } }))
    v.dispatchEvent(new CustomEvent('draw-annotation', { detail: { draw, annotation: { value: 's1:b' }, doc: d, range: fakeRange(d, [{ left: 0, top: 40, right: 100, bottom: 60 }]) } }))
    d.body.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 10, clientY: 50 }))
    expect(ofType('annotationTapped')).toEqual([{ v: 1, type: 'annotationTapped', sessionId: 's1', id: 'b' }])
    expect(ofType('tap')).toEqual([])
    d.body.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 10, clientY: 30 }))
    expect(ofType('tap').length).toBe(1)
    d.querySelector('a')!.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 10, clientY: 10 }))
    expect(ofType('annotationTapped').length).toBe(1) // the link wins (foliate follows it)
    send({ v: 1, type: 'removeAnnotation', sessionId: 's1', id: 'b' }); await settle()
    d.body.dispatchEvent(new MouseEvent('click', { bubbles: true, clientX: 10, clientY: 50 }))
    expect(ofType('annotationTapped').length).toBe(1)
    expect(ofType('tap').length).toBe(2) // a removed one is plain page again
  })

  it('a new open starts with no annotations', async () => {
    const v = await annotated()
    section(v, 1, '<p>One</p>')
    setAnnotations('s1', items(['a', 's1:a'])); await settle()
    send({ v: 1, type: 'close' })
    open('s2', null); await settle()
    const w = FakeView.all.at(-1)!
    w.addAnnotation.mockClear()
    w.dispatchEvent(new CustomEvent('create-overlay', { detail: { index: 1 } }))
    expect(w.addAnnotation).not.toHaveBeenCalled()
  })
})
