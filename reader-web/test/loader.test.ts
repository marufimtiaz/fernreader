import { describe, expect, it, vi } from 'vitest'
import { makeLoader, packageProblem } from '../src/reader/loader'

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

// R-003, C-048: no META-INF/container.xml → the package found by search (the first .opf in name order, as the Kotlin
// scanner picks it, so both compute the same identity); none → "no package".
describe('loader container fallback', () => {
  const with_ = (names: string[], files: Record<string, string> = {}) => vi.fn(async (url: string) => {
    if (url.endsWith('/entries.json')) return new Response(JSON.stringify(names.map(name => ({ name, size: 1 }))))
    const name = decodeURIComponent(url.replace('/book/s-1/', ''))
    return name in files ? new Response(files[name]) : new Response('', { status: 404 })
  }) as unknown as typeof fetch

  it('synthesizes a container for the first .opf when container.xml is missing', async () => {
    const l = await makeLoader('/book/s-1', 'x', with_(['b/second.opf', 'a/Package.OPF', 'a/c.xhtml']))
    const c = await l.loadText('META-INF/container.xml')
    expect(c).toContain('full-path="a/Package.OPF"')
    expect(l.has('META-INF/container.xml')).toBe(true)
  })
  it('keeps a real container.xml', async () => {
    const real = '<container><rootfiles><rootfile full-path="x.opf"/></rootfiles></container>'
    const l = await makeLoader('/book/s-1', 'x', with_(['META-INF/container.xml', 'x.opf', 'y.opf'], { 'META-INF/container.xml': real }))
    expect(await l.loadText('META-INF/container.xml')).toBe(real)
  })
  it('reports no package when there is neither a container nor an .opf', async () => {
    const l = await makeLoader('/book/s-1', 'x', with_(['mimetype', 'OEBPS/c.xhtml']))
    expect(await l.loadText('META-INF/container.xml')).toBeNull()
    expect(await packageProblem(l)).toBe('no_package')
  })
  it('reports nothing readable for a package with an empty spine', async () => {
    const opf = '<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine/></package>'
    const l = await makeLoader('/book/s-1', 'x', with_(['OEBPS/p.opf', 'OEBPS/c.xhtml'], { 'OEBPS/p.opf': opf }))
    expect(await packageProblem(l)).toBe('nothing_readable')
  })
  it('finds no package problem in a sound package', async () => {
    const opf = '<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>'
    const l = await makeLoader('/book/s-1', 'x', with_(['OEBPS/p.opf', 'OEBPS/c.xhtml'], { 'OEBPS/p.opf': opf }))
    expect(await packageProblem(l)).toBeNull()
  })
})
