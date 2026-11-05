import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateRawSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { BookServer, listBooks, mimeOf } from '../dev/bookServer'
import { openZip } from '../dev/zip'

/** A minimal zip: [method] 0 stores, 8 deflates; [comment] goes after the end record. */
function zipOf(files: Record<string, string>, method: 0 | 8 = 8, comment = ''): Buffer {
  const locals: Buffer[] = []
  const centrals: Buffer[] = []
  let offset = 0
  for (const [name, text] of Object.entries(files)) {
    const data = Buffer.from(text)
    const body = method === 8 ? deflateRawSync(data) : data
    const n = Buffer.from(name)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(method, 8)
    local.writeUInt32LE(body.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(n.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(method, 10)
    central.writeUInt32LE(body.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(n.length, 28)
    central.writeUInt32LE(offset, 42)
    locals.push(local, n, body)
    centrals.push(central, n)
    offset += 30 + n.length + body.length
  }
  const cd = Buffer.concat(centrals)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(Object.keys(files).length, 8); end.writeUInt16LE(Object.keys(files).length, 10)
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16); end.writeUInt16LE(comment.length, 20)
  return Buffer.concat([...locals, cd, end, Buffer.from(comment)])
}

const dir = mkdtempSync(join(tmpdir(), 'dev-books-'))
const write = (name: string, data: Buffer | string) => { const p = join(dir, name); writeFileSync(p, data); return p }

describe('openZip', () => {
  it('reads stored and deflated entries, with their uncompressed sizes', () => {
    for (const method of [0, 8] as const) {
      const z = openZip(write(`m${method}.epub`, zipOf({ 'a/b.txt': 'hello hello hello' }, method)))
      expect(z.entries.map(e => [e.name, e.size])).toEqual([['a/b.txt', 17]])
      expect(z.read(z.entry('a/b.txt')!).toString()).toBe('hello hello hello')
      z.close()
    }
  })

  it('finds the end record behind a zip comment', () => {
    const z = openZip(write('comment.epub', zipOf({ x: 'y' }, 8, 'a comment')))
    expect(z.entries.length).toBe(1)
    z.close()
  })

  it('resolves names as the Kotlin ZipReader.entry does: slashes, then case-insensitively', () => {
    const z = openZip(write('names.epub', zipOf({ 'OEBPS/Text.xhtml': '1' })))
    expect(z.entry('OEBPS/Text.xhtml')?.name).toBe('OEBPS/Text.xhtml')
    expect(z.entry('/OEBPS\\Text.xhtml')?.name).toBe('OEBPS/Text.xhtml')
    expect(z.entry('oebps/text.XHTML')?.name).toBe('OEBPS/Text.xhtml')
    expect(z.entry('other')).toBeNull()
    z.close()
  })

  it('refuses a file that is not a zip', () => {
    expect(() => openZip(write('bad.epub', 'not an epub at all\n'))).toThrow(/not a zip/)
  })
})

describe('BookServer (mirrors BookContentServer and BOOK_RESPONSE_HEADERS)', () => {
  const book = write('book.epub', zipOf({ 'dir/': '', 'dir/a b.xhtml': '<html/>', 'mimetype': 'application/epub+zip' }))
  const server = new BookServer(id => (id === 'known' ? book : null))

  it('lists the files of the book without directories in entries.json', () => {
    const r = server.serve('known/entries.json')
    expect(r.status).toBe(200)
    expect(r.mime).toBe('application/json')
    expect(JSON.parse(r.body.toString())).toEqual([{ name: 'dir/a b.xhtml', size: 7 }, { name: 'mimetype', size: 20 }])
  })

  it('serves an entry with its type', () => {
    const r = server.serve('known/dir/a b.xhtml')
    expect([r.status, r.mime, r.body.toString()]).toEqual([200, 'application/xhtml+xml', '<html/>'])
  })

  it('answers 404 for an unknown session or entry and 403 for dot segments', () => {
    expect(server.serve('nobody/entries.json').status).toBe(404)
    expect(server.serve('known').status).toBe(404)
    expect(server.serve('known/nope.css').status).toBe(404)
    expect(server.serve('known/../secret').status).toBe(403)
    expect(server.serve('known/a\\..\\secret').status).toBe(403)
  })

  it('answers 500 for a book that cannot be read, as a damaged zip does in the app', () => {
    const bad = new BookServer(() => write('fake.epub', 'nope'))
    expect(bad.serve('x/entries.json').status).toBe(500)
  })

  it('sends the same headers as the app on every answer', () => {
    for (const p of ['known/entries.json', 'nobody/x']) {
      expect(server.serve(p).headers).toEqual({ 'Cache-Control': 'no-store', 'Content-Security-Policy': "script-src 'none'; object-src 'none'" })
    }
  })
})

describe('mimeOf', () => {
  it('follows MimeTypes.kt', () => {
    expect(mimeOf('a/B.XHTML')).toBe('application/xhtml+xml')
    expect(mimeOf('c.opf')).toBe('application/oebps-package+xml')
    expect(mimeOf('noext')).toBe('application/octet-stream')
  })
})

describe('listBooks', () => {
  it('finds the EPUBs of the corpus folders, with ids free of slashes', () => {
    const corpus = mkdtempSync(join(tmpdir(), 'dev-corpus-'))
    mkdirSync(join(corpus, 'gutenberg'))
    writeFileSync(join(corpus, 'gutenberg', 'b.epub'), '')
    writeFileSync(join(corpus, 'gutenberg', 'a.epub'), '')
    writeFileSync(join(corpus, 'gutenberg', 'notes.txt'), '')
    expect(listBooks(corpus)).toEqual([
      { id: 'gutenberg~a.epub', folder: 'gutenberg', name: 'a.epub', path: join(corpus, 'gutenberg', 'a.epub') },
      { id: 'gutenberg~b.epub', folder: 'gutenberg', name: 'b.epub', path: join(corpus, 'gutenberg', 'b.epub') },
    ])
  })

  it('is empty when there is no corpus', () => {
    expect(listBooks(join(tmpdir(), 'no-such-corpus'))).toEqual([])
  })
})
