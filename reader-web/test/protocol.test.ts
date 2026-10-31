import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Bridge } from '../src/bridge/bridge'
import type { ToWeb } from '../src/bridge/protocol'

const fixture = (n: string) => readFileSync(resolve(__dirname, `../../bridge/fixtures/${n}.json`), 'utf8').trim()

describe('protocol v1', () => {
  it.each(['open', 'close', 'next', 'prev', 'goTo', 'goToSeek', 'setSettings', 'goToFraction', 'goToText', 'clearSearchHighlight'])('dispatches %s to handlers unchanged', name => {
    const seen: ToWeb[] = []
    const b = new Bridge(vi.fn())
    b.onMessage(m => seen.push(m))
    b.receive(fixture(name))
    expect(seen).toEqual([JSON.parse(fixture(name))])
  })
  it.each([{}, { fontScale: 'x' }, { ...JSON.parse(fixture('setSettings')).settings, theme: 'neon' }])('drops setSettings with bad settings %#', settings => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    b.receive(JSON.stringify({ v: 1, type: 'setSettings', settings })); expect(h).not.toHaveBeenCalled()
  })
  it('still ignores unknown types', () => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    b.receive(fixture('unknown')); expect(h).not.toHaveBeenCalled()
  })
  it.each(['null', '42', '"x"', '[]', 'true'])('ignores non-object JSON %s without throwing', json => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    expect(() => b.receive(json)).not.toThrow(); expect(h).not.toHaveBeenCalled()
  })
  it.each(['opened', 'relocate', 'painted', 'seeked', 'tap', 'error', 'pageCounts'])('%s fixture is well-formed JSON with v and type', name => {
    const m = JSON.parse(fixture(name)); expect(m.v).toBe(1); expect(m.type).toBe(name)
  })
  it.each([
    '{"v":1,"type":"open","sessionId":"x"}',
    '{"v":1,"type":"open","sessionId":1,"texts":{"missingPart":"a"}}',
    '{"v":1,"type":"open","sessionId":"x","texts":{"missingPart":2}}',
    '{"v":1,"type":"open","sessionId":"x","position":5,"texts":{"missingPart":"a"}}',
    '{"v":1,"type":"goTo"}',
    '{"v":1,"type":"goTo","target":"c1.xhtml","id":"1"}',
    '{"v":1,"type":"goToText","sectionIndex":1,"offset":2,"length":3}',
    '{"v":1,"type":"goToText","sectionIndex":"1","offset":2,"length":3,"needle":"x"}',
    '{"v":1,"type":"goToText","sectionIndex":-1,"offset":2,"length":3,"needle":"x"}',
    '{"v":1,"type":"goToText","sectionIndex":1,"offset":2.5,"length":3,"needle":"x"}',
  ])('ignores malformed message %s without throwing', json => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    expect(() => b.receive(json)).not.toThrow(); expect(h).not.toHaveBeenCalled()
  })
  it('a throwing handler does not stop later handlers', () => {
    const h = vi.fn(); const b = new Bridge(vi.fn())
    b.onMessage(() => { throw new Error('boom') }); b.onMessage(h)
    expect(() => b.receive(fixture('close'))).not.toThrow(); expect(h).toHaveBeenCalledTimes(1)
  })
})
