import { describe, expect, it, vi } from 'vitest'
import { shareSections } from '../src/reader/sections'

const fake = () => {
  let resolve!: (u: string) => void
  const s = {
    load: vi.fn(() => new Promise<string>(r => { resolve = r })),
    unload: vi.fn(),
    linear: 'yes',
  }
  return { s, done: (u = 'blob:a') => resolve(u) }
}
const tick = () => new Promise(r => setTimeout(r, 0))

describe('shareSections (foliate counts a top-level section only once)', () => {
  it('two users of a section: the real unload runs only after both let go', async () => {
    const { s, done } = fake(); shareSections([s])
    const a = s.load(); const b = s.load(); await tick(); done(); expect(await a).toBe('blob:a'); expect(await b).toBe('blob:a')
    s.unload(); await tick()
    const real = (s as any).__real
    expect(real.load).toHaveBeenCalledTimes(1)
    expect(real.unload).not.toHaveBeenCalled()
    s.unload(); await tick()
    expect(real.unload).toHaveBeenCalledTimes(1)
  })
  it('let go before the load finished (evicted while preloading): released once it finishes, nothing leaks', async () => {
    const { s, done } = fake(); shareSections([s])
    const real = (s as any).__real
    void s.load(); s.unload(); await tick()
    expect(real.unload).not.toHaveBeenCalled() // foliate would have nothing to release yet
    done(); await tick()
    expect(real.unload).toHaveBeenCalledTimes(1)
  })
  it('loading again after the release loads afresh, after the release', async () => {
    const { s, done } = fake(); shareSections([s])
    const real = (s as any).__real
    void s.load(); await tick(); done(); await tick(); s.unload(); await tick()
    real.load.mockImplementation(() => Promise.resolve('blob:b'))
    expect(await s.load()).toBe('blob:b')
    expect(real.load).toHaveBeenCalledTimes(2)
  })
  it('extra unloads are ignored', async () => {
    const { s } = fake(); shareSections([s]); const real = (s as any).__real
    s.unload(); s.unload(); await tick()
    expect(real.unload).not.toHaveBeenCalled()
  })
})
