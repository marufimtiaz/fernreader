import { existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { openZip, type Zip } from './zip'

/**
 * The dev server's stand-in for the app's /book/ handler: the same paths, answers and headers as BookContentServer.kt,
 * MimeTypes.kt and BOOK_RESPONSE_HEADERS in ReaderEngine.kt, so the reader's loader runs unchanged. The app's virtual
 * entries (M8 chunking of huge documents) are not here: a book is served as its plain zip.
 */
const BY_EXT: Record<string, string> = {
  xhtml: 'application/xhtml+xml', xht: 'application/xhtml+xml', html: 'text/html', htm: 'text/html',
  css: 'text/css', svg: 'image/svg+xml', jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png',
  gif: 'image/gif', webp: 'image/webp', ttf: 'font/ttf', otf: 'font/otf', woff: 'font/woff',
  woff2: 'font/woff2', opf: 'application/oebps-package+xml', ncx: 'application/x-dtbncx+xml',
  xml: 'application/xml', smil: 'application/smil+xml', js: 'text/javascript', mp3: 'audio/mpeg',
  mp4: 'video/mp4', m4a: 'audio/mp4',
}

export const mimeOf = (name: string) => {
  const dot = name.lastIndexOf('.')
  return (dot < 0 ? undefined : BY_EXT[name.slice(dot + 1).toLowerCase()]) ?? 'application/octet-stream'
}

const HEADERS = { 'Cache-Control': 'no-store', 'Content-Security-Policy': "script-src 'none'; object-src 'none'" }

export type Served = { status: number; mime: string; body: Buffer; headers: Record<string, string> }

const empty = (status: number): Served => ({ status, mime: 'text/plain', body: Buffer.alloc(0), headers: HEADERS })
const ok = (mime: string, body: Buffer): Served => ({ status: 200, mime, body, headers: HEADERS })

export class BookServer {
  private zips = new Map<string, Zip>()

  /** [locate]: the file of a session (a session id is a book id here), null when there is none. */
  constructor(private locate: (sessionId: string) => string | null) {}

  /** [path] = "<sessionId>/<entry path>", URL-decoded. */
  serve(path: string): Served {
    const slash = path.indexOf('/')
    if (slash <= 0) return empty(404)
    const sid = path.slice(0, slash)
    const name = path.slice(slash + 1)
    if (name.replace(/\\/g, '/').split('/').some(s => s === '.' || s === '..')) return empty(403)
    const file = this.locate(sid)
    if (file == null) return empty(404)
    try {
      const zip = this.zip(file)
      if (name === 'entries.json') {
        const list = zip.entries.filter(e => !e.name.endsWith('/')).map(e => ({ name: e.name, size: e.size }))
        return ok('application/json', Buffer.from(JSON.stringify(list)))
      }
      const e = zip.entry(name)
      return e ? ok(mimeOf(name), zip.read(e)) : empty(404)
    } catch (e) {
      console.warn(`book request failed: ${path}: ${e}`)
      return empty(500)
    }
  }

  private zip(file: string): Zip {
    let z = this.zips.get(file)
    if (!z) { z = openZip(file); this.zips.set(file, z) }
    return z
  }
}

export type DevBook = { id: string; folder: string; name: string; path: string }

export const CORPUS_FOLDERS = ['gutenberg', 'epub3-samples', 'generated']

/** The corpus's books; an id has no '/', since a session id is one path segment in /book/<sessionId>/…. */
export function listBooks(corpus: string): DevBook[] {
  return CORPUS_FOLDERS.flatMap(folder => {
    const dir = join(corpus, folder)
    if (!existsSync(dir)) return []
    return readdirSync(dir).filter(n => n.endsWith('.epub')).sort()
      .map(name => ({ id: `${folder}~${name}`, folder, name, path: join(dir, name) }))
  })
}
