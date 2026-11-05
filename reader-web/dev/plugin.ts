import { readFileSync, statSync } from 'node:fs'
import { relative, resolve } from 'node:path'
import type { Connect, Plugin, ViteDevServer } from 'vite'
import { BookServer, listBooks } from './bookServer'

const here = import.meta.dirname
const webRoot = resolve(here, '..')
const corpus = resolve(process.env.CORPUS_DIR ?? resolve(webRoot, '../corpus'))

/** Modules foliate imports lazily and we never ship (the `external` list of ../vite.config.ts): the dev server needs them to resolve. */
const NOT_SHIPPED = /(^|\/)(vendor\/zip\.js|vendor\/fflate\.js|vendor\/pdfjs\/.*|pdf\.js|comic-book\.js|fb2\.js|mobi\.js|tts\.js)$/
const STUB = '\0dev-not-shipped'

const json = (res: Parameters<Connect.NextHandleFunction>[1], value: unknown) => {
  res.setHeader('Content-Type', 'application/json')
  res.end(JSON.stringify(value))
}

/** How long the last edit took to reach the screen: from the file's write time to the reader's first painted page. */
class EditTimer {
  private pending: { file: string; written: number } | null = null
  last: { file: string; ms: number } | null = null

  edited(file: string) {
    this.pending = { file: relative(webRoot, file), written: statSync(file).mtimeMs }
  }

  painted(at: number) {
    if (!this.pending) return
    this.last = { file: this.pending.file, ms: Math.round(at - this.pending.written) }
    this.pending = null
    console.log(`edit to screen: ${this.last.ms} ms (${this.last.file})`)
  }
}

export function devReader(): Plugin {
  const books = () => listBooks(corpus)
  const server = new BookServer(id => books().find(b => b.id === id)?.path ?? null)
  const timer = new EditTimer()
  return {
    name: 'fernreader-dev',
    enforce: 'pre',
    resolveId: source => (NOT_SHIPPED.test(source) ? STUB : null),
    load: id => (id === STUB ? 'export {}' : null),
    handleHotUpdate({ file }) {
      if (file.startsWith(`${webRoot}/src/`) || file.startsWith(`${webRoot}/vendor/`)) timer.edited(file)
    },
    // The app's reader page, as it is, plus the stand-in for the bridge object.
    transformIndexHtml: {
      order: 'pre',
      handler: (_html, ctx) => (ctx.path === '/src/index.html' ? [{ tag: 'script', attrs: { src: '/dev/shim.js' }, injectTo: 'head-prepend' }] : []),
    },
    configureServer(vite: ViteDevServer) {
      vite.middlewares.use((req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://dev')
        const path = url.pathname
        if (path === '/') { res.statusCode = 302; res.setHeader('Location', '/dev/'); res.end(); return }
        // Not through Vite's HTML pipeline: without its client, an edit reloads the reader frame only, not this page.
        if (path === '/dev/') {
          res.setHeader('Content-Type', 'text/html')
          res.end(readFileSync(resolve(here, 'index.html')))
          return
        }
        if (path.startsWith('/src/fonts/')) { req.url = path.replace('/src/fonts/', '/src/public/fonts/'); next(); return }
        if (path === '/__dev/books') { json(res, { corpus, books: books().map(({ id, folder, name }) => ({ id, folder, name })) }); return }
        if (path === '/__dev/painted') { timer.painted(Date.now()); json(res, timer.last); return }
        if (path === '/__dev/last-edit') { json(res, timer.last); return }
        if (path.startsWith('/book/')) {
          const r = server.serve(decodeURIComponent(path.slice('/book/'.length)))
          res.statusCode = r.status
          res.setHeader('Content-Type', r.mime)
          for (const [k, v] of Object.entries(r.headers)) res.setHeader(k, v)
          res.end(r.body)
          return
        }
        next()
      })
    },
  }
}
