/** foliate-js `EPUB` loader over the Kotlin content server (/book/<sessionId>/…), as in prototypes/foliate host.js. */
export type Loader = Awaited<ReturnType<typeof makeLoader>>

const CONTAINER = 'META-INF/container.xml'
const DOC = /\.(xhtml|xht|html|htm)$/i
const ESC: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }
const esc = (s: string) => s.replace(/[&<>"]/g, c => ESC[c]!)

/** F-084: shown in place of a content document whose file is missing from the archive. */
export const placeholderDoc = (text: string, path: string) =>
  `<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title></title></head>` +
  `<body><p>${esc(text)}</p><p><small>${esc(path)}</small></p></body></html>`

export async function makeLoader(base: string, missingPart: string, fetchFn: typeof fetch = fetch) {
  const res = await fetchFn(`${base}/entries.json`)
  if (!res.ok) throw new Error(`entries.json: HTTP ${res.status}`)
  const list: { name: string; size: number }[] = await res.json()
  const sizes = new Map(list.map(e => [e.name, e.size]))
  // R-003: without META-INF/container.xml, the package found by search: the first .opf in name order, as the Kotlin
  // scanner (PackageLoader) picks it, so the reader and the scanner compute the same identity.
  const opf = sizes.has(CONTAINER) ? null : list.map(e => e.name).filter(n => /\.opf$/i.test(n)).sort()[0] ?? null
  const container = opf == null ? null : `<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="${esc(opf)}" media-type="application/oebps-package+xml"/></rootfiles></container>`
  const url = (name: string) => `${base}/${name.split('/').map(encodeURIComponent).join('/')}`
  const get = (name: string) => sizes.has(name) ? fetchFn(url(name)).then(r => (r.ok ? r : null)) : Promise.resolve(null)
  return {
    entries: list.map(e => ({ filename: e.name, size: e.size })),
    has: (name: string) => sizes.has(name) || (name === CONTAINER && container != null),
    getSize: (name: string) => sizes.get(name) ?? 0,
    loadText: async (name: string): Promise<string | null> => {
      if (name === CONTAINER && container != null) return container
      const r = await get(name)
      if (r) return r.text()
      return DOC.test(name) ? placeholderDoc(missingPart, name) : null
    },
    loadBlob: async (name: string, type?: string): Promise<Blob | null> => {
      const r = await get(name)
      if (r) { const b = await r.blob(); return type && b.type !== type ? new Blob([b], { type }) : b }
      return DOC.test(name) ? new Blob([placeholderDoc(missingPart, name)], { type: 'application/xhtml+xml' }) : null
    },
  }
}

/**
 * C-048: why a book that foliate could not open has nothing to show, in words the app can say: no package document
 * at all (R-003) or a package whose spine lists no item (R-002). Null when neither is the cause. Only called after
 * an open failed, so a sound book never pays for it.
 */
export async function packageProblem(loader: Loader): Promise<'no_package' | 'nothing_readable' | null> {
  const container = await loader.loadText(CONTAINER)
  if (container == null) return 'no_package'
  const parse = (text: string) => new DOMParser().parseFromString(text, 'application/xml')
  const path = parse(container).getElementsByTagNameNS('*', 'rootfile')[0]?.getAttribute('full-path')
  const opf = path ? await loader.loadText(path) : null
  if (opf == null) return 'no_package'
  const doc = parse(opf)
  if (doc.getElementsByTagName('parsererror').length) return null // a broken package keeps its parser message
  return doc.getElementsByTagNameNS('*', 'itemref').length === 0 ? 'nothing_readable' : null
}
