import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Bridge } from '../src/bridge/bridge'
import type { ToWeb } from '../src/bridge/protocol'

const fixture = (n: string) => readFileSync(resolve(__dirname, `../../bridge/fixtures/${n}.json`), 'utf8').trim()

describe('protocol v1', () => {
  it.each(['open', 'close', 'next', 'prev', 'goTo'])('dispatches %s to handlers unchanged', name => {
    const seen: ToWeb[] = []
    const b = new Bridge(vi.fn())
    b.onMessage(m => seen.push(m))
    b.receive(fixture(name))
    expect(seen).toEqual([JSON.parse(fixture(name))])
  })
  it('still ignores unknown types', () => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    b.receive(fixture('unknown')); expect(h).not.toHaveBeenCalled()
  })
  it.each(['null', '42', '"x"', '[]', 'true'])('ignores non-object JSON %s without throwing', json => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    expect(() => b.receive(json)).not.toThrow(); expect(h).not.toHaveBeenCalled()
  })
  it.each(['opened', 'relocate', 'painted', 'tap', 'error'])('%s fixture is well-formed JSON with v and type', name => {
    const m = JSON.parse(fixture(name)); expect(m.v).toBe(1); expect(m.type).toBe(name)
  })
})
