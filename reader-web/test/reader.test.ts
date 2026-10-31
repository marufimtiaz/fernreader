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
  renderer = document.createElement('div')
  lastLocation: any = null
  open = vi.fn(async () => {})
  init = vi.fn(async ({ lastLocation }: { lastLocation?: string | null }) => { await tick(); this.relocateTo(lastLocation ?? 'cfi-start') })
  goTo = vi.fn(async (t: string): Promise<object | undefined> => { await tick(); this.relocateTo(t); return {} })
  close = vi.fn()
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
  constructor() { super(); FakeView.all.push(this) }
  relocateTo(cfi: string) {
    this.lastLocation = { cfi, fraction: 0.25, section: { current: 1, total: 3 }, tocItem: { href: 'c1.xhtml' } }
    this.dispatchEvent(new CustomEvent('relocate', { detail: this.lastLocation }))
  }
}

const book = () => ({ sections: [{ id: 'a' }, { id: 'b' }, { id: 'c' }], toc: [], metadata: { title: 'T', identifier: 'id' }, resolveHref: () => ({ index: 0 }) })
const settle = () => new Promise(r => setTimeout(r, 20))

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
  FakeView.all = []; document.body.innerHTML = ''
  h.makeLoader.mockReset().mockResolvedValue({ has: () => true })
  h.epubInit.mockReset().mockImplementation(async () => book())
  sent = []
  bridge = new Bridge(json => sent.push(JSON.parse(json)))
  new Reader(bridge)
})
afterEach(() => vi.restoreAllMocks())

describe('Reader open', () => {
  it('first open sends opened, relocate, painted', async () => {
    open('s1', 'cfi-a'); await settle()
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
