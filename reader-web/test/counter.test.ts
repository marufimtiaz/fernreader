import { describe, expect, it } from 'vitest'
import { planBatch, readingOrder, stratifiedSample } from '../src/reader/counter'

describe('stratifiedSample (S1: 12 sections spread over the book)', () => {
  const linear = Array.from({ length: 368 }, (_, i) => i)
  it('takes 12 distinct sections spread evenly, not the front matter only', () => {
    const s = stratifiedSample(linear, 12)
    expect(s).toHaveLength(12)
    expect(new Set(s).size).toBe(12)
    expect(s[0]).toBeGreaterThan(0)
    expect(s[11]).toBeGreaterThan(330)
    expect([...s].sort((a, b) => a - b)).toEqual(s)
    expect(stratifiedSample(linear, 12)).toEqual(s) // deterministic
  })
  it('a short book is sampled whole', () => {
    expect(stratifiedSample([2, 5, 7], 12)).toEqual([2, 5, 7])
  })
  it('respects non-linear gaps (only linear indexes)', () => {
    const lin = [0, 1, 3, 4, 6, 8, 9, 10, 12, 13, 15, 16, 17, 19, 20]
    expect(stratifiedSample(lin, 12).every(i => lin.includes(i))).toBe(true)
  })
})

describe('readingOrder', () => {
  it('sample first, then every other linear section once, in reading order', () => {
    const linear = Array.from({ length: 30 }, (_, i) => i)
    const sample = stratifiedSample(linear, 12)
    const order = readingOrder(linear, sample)
    expect(order.slice(0, 12)).toEqual(sample)
    expect([...order].sort((a, b) => a - b)).toEqual(linear)
    const rest = order.slice(12)
    expect([...rest].sort((a, b) => a - b)).toEqual(rest)
  })
})

describe('planBatch (S1 risks: only sections the batch box reproduces exactly share a document)', () => {
  it('batches the sections with the most common head style sheets and body attributes; the rest are counted alone', () => {
    const p = planBatch([
      { index: 3, key: 'A' }, { index: 4, key: 'A' }, { index: 5, key: 'B' }, { index: 6, key: 'A' }, { index: 7, key: 'C' },
    ])
    expect(p.key).toBe('A')
    expect(p.batched).toEqual([3, 4, 6])
    expect(p.alone).toEqual([5, 7])
  })
  it('a tie takes the first key in reading order', () => {
    expect(planBatch([{ index: 1, key: 'B' }, { index: 2, key: 'A' }]).key).toBe('B')
  })
  it('a section whose document could not be read is counted alone', () => {
    expect(planBatch([{ index: 1, key: 'A' }, { index: 2, key: null }]).alone).toEqual([2])
  })
})
