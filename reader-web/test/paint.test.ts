import { describe, expect, it, vi } from 'vitest'
import { decoded, FIRST_PAINT_WAIT_MS, pageImages } from '../src/reader/paint'

// C-089: the first `painted` waits for the decode of the images on the page, bounded.

const img = (rect: Partial<DOMRect>) => ({ getBoundingClientRect: () => rect, decode: async () => {} })
const contents = (els: unknown[], left = 0) => [{
  doc: { querySelectorAll: () => els, defaultView: { frameElement: { getBoundingClientRect: () => ({ left, top: 0 }) } } } as unknown as Document,
}]

describe('pageImages', () => {
  it('keeps an image on the page and drops one on a later page', () => {
    const on = img({ left: 10, top: 10, width: 100, height: 100 }), off = img({ left: 500, top: 10, width: 100, height: 100 })
    expect(pageImages(contents([on, off]), 400, 800)).toEqual([on])
  })
  it('counts the frame offset: paged flow moves the frame, not the image', () => {
    const el = img({ left: 810, top: 10, width: 100, height: 100 })
    expect(pageImages(contents([el], -800), 400, 800)).toEqual([el])
    expect(pageImages(contents([el]), 400, 800)).toEqual([])
  })
  it('skips an image with no box', () => expect(pageImages(contents([img({ left: 0, top: 0, width: 0, height: 0 })]), 400, 800)).toEqual([]))
  it('a section with no document has nothing to wait for', () => expect(pageImages([{}], 400, 800)).toEqual([]))
})

describe('decoded', () => {
  it('resolves when the images are decoded', async () => {
    let finish = () => {}
    const pending = new Promise<void>(r => { finish = r })
    let done = false
    const p = decoded([{ decode: () => pending }]).then(() => { done = true })
    await Promise.resolve()
    expect(done).toBe(false)
    finish(); await p
    expect(done).toBe(true)
  })
  it('a broken image does not hold the page back', async () => {
    await decoded([{ decode: () => Promise.reject(new Error('broken')) }])
  })
  it('gives up after the timeout', async () => {
    vi.useFakeTimers()
    let done = false
    void decoded([{ decode: () => new Promise<void>(() => {}) }]).then(() => { done = true })
    await vi.advanceTimersByTimeAsync(FIRST_PAINT_WAIT_MS - 1)
    expect(done).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(done).toBe(true)
    vi.useRealTimers()
  })
})
