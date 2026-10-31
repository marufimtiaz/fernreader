import { describe, expect, it } from 'vitest'
import { boxPages, splitSource, planBatch, readingOrder, stratifiedSample } from '../src/reader/counter'

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

describe('boxPages (pages of one column box, as foliate View.expand counts them)', () => {
  const box = { left: 0, right: 370 }
  it('left-to-right columns grow to the right', () => {
    expect(boxPages({ left: 20, right: 3600 }, box, 370)).toBe(10)
  })
  it('right-to-left columns grow to the left (F-081)', () => {
    expect(boxPages({ left: 370 - 3600, right: 350 }, box, 370)).toBe(10)
  })
  it('never less than one page', () => {
    expect(boxPages({ left: 0, right: 0 }, box, 370)).toBe(1)
  })
})

describe('splitSource (a section cut into what the batch document needs, without building a DOM)', () => {
  const src = `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>t</title>
<link rel="stylesheet" href="blob:a"/><link rel="icon" href="x"/><style>p{margin:0}</style></head>
<body class="x-ebookmaker"><p>One</p><p>Two</p></body></html>`
  it('keeps style sheets, html and body attributes and the body markup', () => {
    const x = splitSource(src)!
    expect(x.head).toEqual(['<link rel="stylesheet" href="blob:a"/>', '<style>p{margin:0}</style>'])
    expect(x.htmlAttrs).toBe('lang="en"')
    expect(x.bodyAttrs).toBe('class="x-ebookmaker"')
    expect(x.body).toBe('<p>One</p><p>Two</p>')
  })
  it('no body: null (counted alone)', () => {
    expect(splitSource('<html><p>x</p></html>')).toBeNull()
  })
})
