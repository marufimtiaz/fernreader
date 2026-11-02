import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { EPUB, fixSvgAttributeCase } from '../vendor/foliate-js/epub.js'

// C-081 (local patch in epub.js): a cover page made of an inline SVG wrapping one image. The image is referenced with a
// plain SVG 2 `href` or with `xlink:href`; viewBox/preserveAspectRatio may be written in lowercase. A synthetic book.

const cover = (ref: string) => `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink"><head><title>Cover</title></head>
<body><div><svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewbox="0 0 900 1129" preserveaspectratio="xMidYMid meet"><image width="900" height="1129" ${ref}="cover.jpeg"/></svg></div></body></html>`

const files = (ref: string): Record<string, string> => ({
  'META-INF/container.xml': `<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`,
  'OEBPS/content.opf': `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">c081</dc:identifier><dc:title>SVG cover</dc:title></metadata>
<manifest><item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/><item id="img" href="cover.jpeg" media-type="image/jpeg"/></manifest><spine><itemref idref="cover"/></spine></package>`,
  'OEBPS/cover.xhtml': cover(ref),
  'OEBPS/cover.jpeg': 'JPEGDATA',
})

// foliate's EPUB.init uses Object.groupBy (Node 21+, every WebView we target); older test hosts get a small one.
if (!(Object as any).groupBy) (Object as any).groupBy = <T>(xs: Iterable<T>, key: (x: T, i: number) => PropertyKey) => {
  const out: Record<PropertyKey, T[]> = Object.create(null)
  let i = 0
  for (const x of xs) (out[key(x, i++)] ??= []).push(x)
  return out
}

if (!(Map as any).groupBy) (Map as any).groupBy = <T>(xs: Iterable<T>, key: (x: T, i: number) => unknown) => {
  const out = new Map<unknown, T[]>()
  let i = 0
  for (const x of xs) { const k = key(x, i++); out.set(k, [...(out.get(k) ?? []), x]) }
  return out
}

let blobs: Map<string, Blob>
beforeEach(() => {
  blobs = new Map()
  let n = 0
  vi.stubGlobal('URL', Object.assign(class extends URL {}, {
    createObjectURL: (b: Blob) => { const u = `blob:test/${++n}`; blobs.set(u, b); return u },
    revokeObjectURL: () => {},
  }))
})
afterEach(() => vi.unstubAllGlobals())

/** jsdom's Blob has no text(). */
const textOf = (b: Blob) => new Promise<string>((ok, fail) => {
  const r = new FileReader(); r.onload = () => ok(r.result as string); r.onerror = () => fail(r.error); r.readAsText(b)
})

async function coverDoc(ref: string): Promise<{ doc: Document; urls: Map<string, Blob> }> {
  const f = files(ref)
  const loader = {
    entries: Object.keys(f).map(filename => ({ filename, size: f[filename].length })),
    loadText: async (name: string) => f[name] ?? null,
    loadBlob: async (name: string, type?: string) => (name in f ? new Blob([f[name]], { type }) : null),
    getSize: (name: string) => f[name]?.length ?? 0,
  }
  const book: any = await new EPUB(loader as any).init()
  const url: string = await book.sections[0].load()
  const text = await textOf(blobs.get(url)!)
  return { doc: new DOMParser().parseFromString(text, 'application/xhtml+xml'), urls: blobs }
}

describe('C-081: an SVG-wrapped cover image loads with either href form', () => {
  for (const ref of ['href', 'xlink:href']) {
    it(`<image ${ref}> is rewritten to a loadable URL holding the image`, async () => {
      const { doc, urls } = await coverDoc(ref)
      const image = doc.getElementsByTagNameNS('http://www.w3.org/2000/svg', 'image')[0]
      const value = ref === 'href' ? image.getAttribute('href') : image.getAttributeNS('http://www.w3.org/1999/xlink', 'href')
      expect(value).toMatch(/^blob:test\//)
      expect(await textOf(urls.get(value!)!)).toBe('JPEGDATA')
    })
    it(`lowercase viewbox / preserveaspectratio are honoured (${ref})`, async () => {
      const { doc } = await coverDoc(ref)
      const svg = doc.getElementsByTagNameNS('http://www.w3.org/2000/svg', 'svg')[0]
      expect(svg.getAttribute('viewBox')).toBe('0 0 900 1129')
      expect(svg.getAttribute('preserveAspectRatio')).toBe('xMidYMid meet')
    })
  }
})

describe('fixSvgAttributeCase', () => {
  const parse = (svg: string) => new DOMParser().parseFromString(
    `<html xmlns="http://www.w3.org/1999/xhtml"><body>${svg}</body></html>`, 'application/xhtml+xml')
  it('a proper-cased attribute wins over the lowercase one', () => {
    const doc = parse('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10" viewbox="0 0 99 99"/>')
    fixSvgAttributeCase(doc)
    expect(doc.getElementsByTagNameNS('http://www.w3.org/2000/svg', 'svg')[0].getAttribute('viewBox')).toBe('0 0 10 10')
  })
  it('an svg without either attribute is left alone', () => {
    const doc = parse('<svg xmlns="http://www.w3.org/2000/svg" width="10"/>')
    fixSvgAttributeCase(doc)
    const svg = doc.getElementsByTagNameNS('http://www.w3.org/2000/svg', 'svg')[0]
    expect(svg.getAttributeNames()).toEqual(['xmlns', 'width'])
  })
})
