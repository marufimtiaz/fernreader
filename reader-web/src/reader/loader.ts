/** foliate-js `EPUB` loader over the Kotlin content server (/book/<sessionId>/…), as in prototypes/foliate host.js. */
export type Loader = Awaited<ReturnType<typeof makeLoader>>

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
  const url = (name: string) => `${base}/${name.split('/').map(encodeURIComponent).join('/')}`
  const get = (name: string) => sizes.has(name) ? fetchFn(url(name)).then(r => (r.ok ? r : null)) : Promise.resolve(null)
  return {
    entries: list.map(e => ({ filename: e.name, size: e.size })),
    has: (name: string) => sizes.has(name),
    getSize: (name: string) => sizes.get(name) ?? 0,
    loadText: async (name: string): Promise<string | null> => {
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
