import { describe, expect, it } from 'vitest'
import { BATCH, BATCH_BYTES, batches, boxPages, splitSource, planBatch, readingOrder, stratifiedSample } from '../src/reader/counter'

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

describe('batches (C-062: a batch document holds at most BATCH sections and about BATCH_BYTES of markup)', () => {
  const ids = (n: number) => Array.from({ length: n }, (_, i) => i)
  it('small sections: the sample, then BATCH at a time', () => {
    const b = batches(ids(60), 12, () => 10_000)
    expect(b.map(x => x.length)).toEqual([12, BATCH, 60 - 12 - BATCH])
  })
  it('huge parts go a few at a time, never more than BATCH_BYTES per document (one part alone if it is bigger)', () => {
    const b = batches(ids(80), 12, () => 130_000)
    expect(Math.max(...b.map(x => x.length))).toBe(Math.floor(BATCH_BYTES / 130_000))
    expect(b.flat()).toEqual(ids(80))
    expect(batches([0, 1], 0, () => 5 * BATCH_BYTES).map(x => x.length)).toEqual([1, 1])
  })
  it('the sample stays one message (the estimate) but is split into documents by size', () => {
    const b = batches(ids(20), 12, i => (i < 12 ? 200_000 : 1_000))
    expect(b.slice(0, -1).flat()).toEqual(ids(12)) // sample documents first
    expect(b.at(-1)).toEqual(ids(20).slice(12))
  })
  it('an unknown size counts as small', () => {
    expect(batches(ids(30), 0, () => undefined).map(x => x.length)).toEqual([BATCH, 6])
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

describe('PageCounter alone mode (C-032: vertical books counted one section at a time in a foliate View)', () => {
  it('counts every section alone, sample first, never fetching batch sources, and reports ≈ then exact', async () => {
    const { PageCounter } = await import('../src/reader/counter')
    const sections = Array.from({ length: 15 }, (_, i) => ({ load: async () => `blob:${i}`, linear: i === 3 ? 'no' : undefined }))
    const layout = { width: 400, height: 800, gap: 28, columnWidth: 372, margin: 32, verticalPages: true }
    const renderer = { pagedLayout: layout, styles: ['', ''] as [string, string], busy: false }
    const msgs: any[] = []
    const counter = new PageCounter(sections, () => renderer, () => ({}) as any, 'ja', '', m => msgs.push(m), { alone: true })
    const counted: number[] = []
    ;(counter as any).countAlone = async (i: number) => { counted.push(i); return i + 1 }
    const realFetch = globalThis.fetch
    let fetched = 0
    globalThis.fetch = (async () => { fetched++; throw new Error('no fetch') }) as any
    try { await counter.start(1) } finally { globalThis.fetch = realFetch }
    expect(fetched).toBe(0)
    const linear = sections.map((_, i) => i).filter(i => i !== 3)
    expect([...counted].sort((a, b) => a - b)).toEqual(linear)
    expect(counted.slice(0, 12)).toEqual(stratifiedSample(linear, 12))
    const withCounts = msgs.filter(m => m.counts.length)
    expect(withCounts[0].sample).toBe(true)
    expect(withCounts.at(-1).done).toBe(true)
    expect(Object.fromEntries(withCounts.flatMap(m => m.counts))).toEqual(Object.fromEntries(linear.map(i => [i, i + 1])))
  })
  it('big sections: the sample split over several documents is still one sample message; done only on the last (C-062)', async () => {
    const { PageCounter } = await import('../src/reader/counter')
    const sections = Array.from({ length: 30 }, (_, i) => ({ load: async () => `blob:${i}`, size: 200_000 }))
    const renderer = { pagedLayout: { width: 400, height: 800, gap: 28, columnWidth: 372, margin: 32 }, styles: '', busy: false }
    const msgs: any[] = []
    const counter = new PageCounter(sections, () => renderer, () => ({}) as any, 'en', 'ltr', m => msgs.push(m), { alone: true })
    ;(counter as any).countAlone = async (i: number) => i + 1
    await counter.start(1)
    const withCounts = msgs.filter(m => m.counts.length)
    const sampleMsgs = withCounts.filter(m => m.sample)
    expect(sampleMsgs).toHaveLength(1)
    expect(sampleMsgs[0].counts.map((c: number[]) => c[0])).toEqual(stratifiedSample(sections.map((_, i) => i), 12))
    expect(withCounts[0]).toBe(sampleMsgs[0])
    expect(withCounts.filter(m => m.done)).toEqual([withCounts.at(-1)])
  })
  it('stopped while the sample is held: no sample message is sent', async () => {
    const { PageCounter } = await import('../src/reader/counter')
    const sections = Array.from({ length: 30 }, (_, i) => ({ load: async () => `blob:${i}`, size: 200_000 }))
    const renderer = { pagedLayout: { width: 400, height: 800, gap: 28, columnWidth: 372, margin: 32 }, styles: '', busy: false }
    const msgs: any[] = []
    const counter = new PageCounter(sections, () => renderer, () => ({}) as any, 'en', 'ltr', m => msgs.push(m), { alone: true })
    let n = 0
    ;(counter as any).countAlone = async (i: number) => { if (++n === 3) counter.stop(); return i + 1 }
    await counter.start(1)
    expect(msgs.filter(m => m.counts.length)).toEqual([])
  })
  it('waits while a page turn runs before each section', async () => {
    const { PageCounter } = await import('../src/reader/counter')
    const sections = [{ load: async () => 'a' }, { load: async () => 'b' }]
    const renderer = { pagedLayout: { width: 400, height: 800, gap: 28, columnWidth: 372, margin: 32 }, styles: '', busy: false }
    const counter = new PageCounter(sections, () => renderer, () => ({}) as any, 'ja', '', () => {}, { alone: true })
    const busyAt: boolean[] = []
    ;(counter as any).countAlone = async () => { busyAt.push(renderer.busy); renderer.busy = true; setTimeout(() => { renderer.busy = false }, 60); return 1 }
    await counter.start(1)
    expect(busyAt).toEqual([false, false])
  })
})
