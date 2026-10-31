import { describe, expect, it, vi } from 'vitest'
import { makeLoader } from '../src/reader/loader'

const entries = [{ name: 'OEBPS/content.opf', size: 10 }, { name: 'OEBPS/Text/Livres Été.xhtml', size: 5 }]
const fakeFetch = vi.fn(async (url: string) => {
  if (url.endsWith('/entries.json')) return new Response(JSON.stringify(entries))
  if (url === '/book/s-1/OEBPS/Text/Livres%20%C3%89t%C3%A9.xhtml') return new Response('<html/>')
  return new Response('', { status: 404 })
})

describe('loader', () => {
  it('lists entries in the shape foliate expects and encodes URL segments', async () => {
    const l = await makeLoader('/book/s-1', 'Missing part', fakeFetch as unknown as typeof fetch)
    expect(l.entries.map(e => e.filename)).toEqual(entries.map(e => e.name))
    expect(await l.loadText('OEBPS/Text/Livres Été.xhtml')).toBe('<html/>')
  })
  it('serves a placeholder for a missing content document (F-084) and null for other missing entries', async () => {
    const l = await makeLoader('/book/s-1', 'Missing <part>', fakeFetch as unknown as typeof fetch)
    const doc = await l.loadText('OEBPS/gone.xhtml')
    expect(doc).toContain('Missing &lt;part&gt;'); expect(doc).toContain('OEBPS/gone.xhtml')
    expect(await l.loadBlob('OEBPS/gone.png')).toBeNull()
    expect(l.has('OEBPS/gone.xhtml')).toBe(false)
  })
})
