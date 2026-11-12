import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FromWeb, ToWeb } from '../src/bridge/protocol'
import { DEFAULT_SETTINGS } from '../src/reader/styles'
import { FakeHost, tapAction, type HostStore } from '../dev/host'

const memory = (): HostStore => {
  const m = new Map<string, string>()
  return { get: k => m.get(k) ?? null, set: (k, v) => { m.set(k, v) } }
}

describe('FakeHost', () => {
  let sent: ToWeb[]
  let shown: FromWeb[]
  let store: HostStore
  let host: FakeHost
  const web = (m: unknown) => host.fromWeb(JSON.stringify({ v: 1, ...(m as object) }))
  const relocate = (cfi: string) => web({ type: 'relocate', sessionId: 'b1', cfi, fraction: 0.5, sectionIndex: 1, tocHref: null, page: 1, pages: 2, sectionFraction: 0 })

  beforeEach(() => {
    sent = []; shown = []; store = memory()
    host = new FakeHost(m => sent.push(m), m => shown.push(m), store)
  })

  it('opens the chosen book once the reader says ready, as the app does', () => {
    host.book = 'b1'
    expect(sent).toEqual([])
    web({ type: 'ready', engine: 'x' })
    expect(sent).toHaveLength(1)
    expect(sent[0]).toMatchObject({ type: 'open', sessionId: 'b1', position: null, settings: DEFAULT_SETTINGS })
    expect((sent[0] as any).texts.missingPart).toBeTruthy()
  })

  it('reopens the same place after the reader page reloaded (an edit)', () => {
    host.book = 'b1'
    web({ type: 'ready', engine: 'x' })
    relocate('epubcfi(/6/4)')
    web({ type: 'ready', engine: 'x' })
    expect(sent.at(-1)).toMatchObject({ type: 'open', sessionId: 'b1', position: 'epubcfi(/6/4)' })
  })

  it('keeps position and settings across a reload of the dev page', () => {
    host.book = 'b1'
    host.changeSettings({ flow: 'scrolled' })
    web({ type: 'ready', engine: 'x' })
    relocate('epubcfi(/6/8)')
    const after = new FakeHost(m => sent.push(m), () => {}, store)
    after.book = 'b1'
    after.fromWeb(JSON.stringify({ v: 1, type: 'ready', engine: 'x' }))
    expect(sent.at(-1)).toMatchObject({ type: 'open', position: 'epubcfi(/6/8)', settings: { flow: 'scrolled' } })
  })

  it('sends a settings change once the reader is ready, and only then', () => {
    host.book = 'b1'
    host.changeSettings({ marginPercent: 8 })
    expect(sent).toEqual([])
    web({ type: 'ready', engine: 'x' })
    host.changeSettings({ fontScale: 1.2 })
    expect(sent.at(-1)).toEqual({ v: 1, type: 'setSettings', settings: { ...DEFAULT_SETTINGS, marginPercent: 8, fontScale: 1.2 } })
  })

  it('closes the open book before the next one', () => {
    host.book = 'b1'
    web({ type: 'ready', engine: 'x' })
    host.openBook('b2')
    expect(sent.slice(-2).map(m => m.type)).toEqual(['close', 'open'])
    expect(sent.at(-1)).toMatchObject({ sessionId: 'b2' })
  })

  it('turns pages on the tap zones, mirrored for a right-to-left book', () => {
    host.book = 'b1'
    web({ type: 'ready', engine: 'x' })
    web({ type: 'tap', sessionId: 'b1', x: 0.9, y: 0.5 })
    expect(sent.at(-1)?.type).toBe('next')
    web({ type: 'opened', sessionId: 'b1', identifier: null, title: 't', authors: [], dir: 'rtl', fixedLayout: false, sections: 1, toc: [], sizes: [] })
    web({ type: 'tap', sessionId: 'b1', x: 0.9, y: 0.5 })
    expect(sent.at(-1)?.type).toBe('prev')
  })

  it('shows every message from the reader, and ignores what is not JSON', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    host.book = 'b1'
    web({ type: 'ready', engine: 'x' })
    host.fromWeb('not json')
    expect(shown.map(m => m.type)).toEqual(['ready'])
    warn.mockRestore()
  })
})

describe('tapAction (as PageTurnInput.kt, tap zones on)', () => {
  it('has outer zones and a centre', () => {
    expect(tapAction(0.1, false)).toBe('prev')
    expect(tapAction(0.5, false)).toBe('controls')
    expect(tapAction(0.3, false)).toBe('none')
    expect(tapAction(0.9, false)).toBe('next')
    expect(tapAction(0.1, true)).toBe('next')
  })
})
