/** foliate-js `EPUB` loader over the Kotlin content server (/book/<sessionId>/…), as in prototypes/foliate host.js. */
export type Loader = Awaited<ReturnType<typeof makeLoader>>

const CONTAINER = 'META-INF/container.xml'
const ROOTFILE = /<(?:[\w.-]+:)?rootfile\b([^>]*)>/g
const ATTR = /([\w.:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g
const ENT: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }
const unescape = (s: string) => s.replace(/&(amp|lt|gt|quot|apos);/g, (_, n) => ENT[n]!)

/**
 * The package path by the Kotlin scanner's rule (ContainerParser + PackageLoader): the container's rootfile with the
 * oebps-package media type, else its first rootfile, if that names an entry; otherwise the first .opf in name order.
 * The container is read leniently (attributes of rootfile tags), as the scanner's lenient XML tree does.
 */
export function packagePath(container: string | null, entryName: (p: string) => string | null, names: string[]): string | null {
  if (container != null) {
    const rootfiles = [...container.matchAll(ROOTFILE)].map(m => {
      const attrs = new Map<string, string>()
      for (const a of m[1]!.matchAll(ATTR)) attrs.set(a[1]!.toLowerCase().replace(/^.*:/, ''), unescape(a[2] ?? a[3] ?? ''))
      return attrs
    })
    const chosen = rootfiles.find(r => r.get('media-type') === 'application/oebps-package+xml') ?? rootfiles[0]
    const path = chosen?.get('full-path')
    const found = path && path.trim() ? entryName(path) : null
    if (found != null) return found
  }
  return names.filter(n => /\.opf$/i.test(n)).sort()[0] ?? null
}

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
  const lower = new Map<string, string>()
  for (const e of list) if (!lower.has(e.name.toLowerCase())) lower.set(e.name.toLowerCase(), e.name)
  /** An entry name as the Kotlin ZipReader.entry resolves a path: backslashes, a leading '/', then case-insensitively. */
  const entryName = (path: string): string | null => {
    const p = path.replace(/\\/g, '/').replace(/^\//, '')
    return sizes.has(p) ? p : lower.get(p.toLowerCase()) ?? null
  }
  const url = (name: string) => `${base}/${name.split('/').map(encodeURIComponent).join('/')}`
  const containerName = entryName(CONTAINER)
  let containerText: string | null = null
  if (containerName != null) {
    const r = await fetchFn(url(containerName))
    if (!r.ok) throw new Error(`${CONTAINER}: HTTP ${r.status}`)
    containerText = await r.text()
  }
  // R-003, review I1: foliate always gets a container naming the package the Kotlin scanner picks (ContainerParser,
  // PackageLoader), so a book the library added opens with the same package and identity.
  const opf = packagePath(containerText, entryName, list.map(e => e.name))
  const container = opf == null ? null : `<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="${esc(opf)}" media-type="application/oebps-package+xml"/></rootfiles></container>`
  const get = (name: string) => sizes.has(name) ? fetchFn(url(name)).then(r => (r.ok ? r : null)) : Promise.resolve(null)
  return {
    /** The package document the reader uses (null: none in the file). */
    opf,
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
  if (loader.opf == null) return 'no_package'
  const opf = await loader.loadText(loader.opf)
  if (opf == null) return 'no_package'
  const doc = new DOMParser().parseFromString(opf, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) return null // a broken package keeps its parser message
  return doc.getElementsByTagNameNS('*', 'itemref').length === 0 ? 'nothing_readable' : null
}
