// Thin host around foliate-js: loads the book through the Kotlin zip server and talks to Kotlin via `Android`.
import '../foliate-js/view.js'
import { EPUB } from '../foliate-js/epub.js'

const A = window.Android
const log = m => A.log(String(m))
let view, book, fontScale = 1

const makeLoader = async () => {
    const res = await fetch('/meta/entries')
    if (!res.ok) throw new Error(await res.text())
    const entries = await res.json()
    const sizes = new Map(entries.map(e => [e.filename, e.size]))
    const url = name => '/book/' + name.split('/').map(encodeURIComponent).join('/')
    const get = name => sizes.has(name) ? fetch(url(name)).then(r => r.ok ? r : null) : Promise.resolve(null)
    return {
        entries,
        loadText: name => get(name).then(r => r?.text() ?? null),
        loadBlob: (name, type) => get(name).then(r => r?.blob() ?? null)
            .then(b => b && type && b.type !== type ? new Blob([b], { type }) : b),
        getSize: name => sizes.get(name) ?? 0,
    }
}

const css = () => `
    html { font-size: ${Math.round(fontScale * 100)}% !important; }
    p, li, blockquote, dd { line-height: 1.5; }
    pre { white-space: pre-wrap !important; }
`

const onRelocate = d => {
    const r = view.renderer
    const pages = r.pages ? r.pages - 2 : 0
    const label = `${r.page ?? '?'}/${pages} · sec ${d.section?.current + 1}/${d.section?.total}`
        + ` · loc ${d.location?.current}/${d.location?.total}`
    A.relocate(label, d.cfi ?? '')
    requestAnimationFrame(() => requestAnimationFrame(() => A.painted(label)))
}

window.openBook = async () => {
    try {
        const loader = await makeLoader()
        log('loader_ready entries=' + loader.entries.length)
        book = await new EPUB(loader).init()
        if (!book.sections?.length) throw new Error('The book has no readable content (empty spine).')
        log('book_parsed sections=' + book.sections.length)
        view = document.createElement('foliate-view')
        document.body.append(view)
        view.addEventListener('relocate', e => onRelocate(e.detail))
        view.addEventListener('load', e => log('section_load index=' + e.detail.index))
        view.addEventListener('external-link', e => { e.preventDefault(); log('external_link ' + e.detail.a.href) })
        await view.open(book)
        log('view_opened')
        const r = view.renderer
        r.setAttribute('flow', 'paginated')
        r.setAttribute('animated', '')
        r.setAttribute('margin', '24px')
        r.setAttribute('gap', '6%')
        r.setAttribute('max-column-count', '1')
        r.setStyles?.(css())
        log('styled')
        const last = A.lastLocation()
        await view.init({ lastLocation: last || null })
        A.opened(JSON.stringify({ sections: book.sections.length, fxl: view.isFixedLayout, dir: book.dir }))
    } catch (e) {
        A.error(String(e?.message ?? e))
    }
}

window.next = () => view?.goRight()
window.prev = () => view?.goLeft()
window.setFont = s => { fontScale = s; view?.renderer.setStyles?.(css()) }
window.goTo = target => view?.goTo(target).catch(e => log('goto_error ' + e))

window.getToc = () => {
    const out = []
    const walk = (items, depth) => {
        for (const i of items ?? []) {
            out.push({ label: (i.label ?? '').trim(), depth, href: i.href })
            walk(i.subitems, depth + 1)
        }
    }
    walk(book?.toc, 0)
    A.tocResult(JSON.stringify(out))
}

let searchGen = null
window.search = async query => {
    const gen = view.search({ query })
    searchGen = gen
    let n = 0
    for await (const r of gen) {
        if (searchGen !== gen) return
        if (r === 'done') break
        if (r.subitems) {
            A.searchHits(JSON.stringify(r.subitems.map(s => ({
                label: r.label,
                snippet: `${s.excerpt.pre}[${s.excerpt.match}]${s.excerpt.post}`,
                cfi: s.cfi,
            }))))
            n += r.subitems.length
            if (n >= 5000) break
        }
    }
    if (searchGen === gen) A.searchDone()
}
window.cancelSearch = () => { searchGen?.return?.(); searchGen = null }

window.addEventListener('error', e => log('onerror ' + e.message))
window.addEventListener('unhandledrejection', e => log('rejection ' + (e.reason?.stack ?? e.reason)))
A.hostReady()
