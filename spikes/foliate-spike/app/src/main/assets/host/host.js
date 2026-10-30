// Thin host around foliate-js: loads the book through the Kotlin zip server and talks to Kotlin via `Android`.
import '../foliate-js/view.js'
import { EPUB } from '../foliate-js/epub.js'
import { countAll } from './count.js'

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
        if (A.preloadEnabled?.()) { r.setAttribute('preload', ''); log('preload_on') } // SPIKE S2
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

// SPIKE (M0 S1): page counting commands.
let counting = null
window.countAll = async (strategy, arg) => {
    const r = view.renderer
    const layout = r.layout
    log(`count_start strategy=${strategy} arg=${arg} w=${layout.width} h=${layout.height} gap=${layout.gap.toFixed(2)} col=${layout.columnWidth.toFixed(2)}`)
    const opts = { strategy, layout, styles: r.styles, lang: view.language?.canonical,
        dir: view.language?.isCJK ? '' : view.language?.direction }
    if (strategy === 'batched' || strategy === 'stratified') opts.batch = arg || 24; else opts.lanes = arg || 2
    if (strategy === 'stratified') { opts.strategy = 'batched'; opts.stratified = true }
    // 'mixed': stratified first batch of 12, then batches of `arg` (default 96)
    if (strategy === 'mixed') { opts.strategy = 'batched'; opts.stratified = 12; opts.batch = arg || 96 }
    // Estimate after the first (stratified) batch: previous layout's exact counts scaled by the sample ratio
    // when available, else the text-length model (chars per page from the sample, last page half full).
    const prev = window.__lastCounts
    const onBatch = (idx, res, ms, sample) => {
        if (!sample) return
        const S = new Set(sample)
        let est
        if (prev) {
            const r = sample.reduce((a, i) => a + res[i], 0) / Math.max(1, sample.reduce((a, i) => a + prev[i], 0))
            est = prev.reduce((a, p, i) => a + (S.has(i) ? res[i] : p * r), 0)
        } else if (window.__chars) {
            const ch = window.__chars, S2 = sample.filter(i => ch[i] >= 200)
            const C = S2.reduce((a, i) => a + ch[i], 0) / Math.max(1, S2.reduce((a, i) => a + res[i] - 0.5, 0))
            est = ch.reduce((a, c, i) => a + (S.has(i) ? res[i] : c < 200 ? 1 : Math.ceil(c / C)), 0)
        }
        log(`count_estimate ms=${Math.round(ms)} est=${Math.round(est)} basis=${prev ? 'scale' : 'text'}`)
    }
    counting = countAll(book, opts, (i, p) => { if (i % 50 === 0) log(`count_progress i=${i}`) }, onBatch)
    const res = await counting
    counting = null
    window.__lastCounts = res.results
    log(`count_done strategy=${strategy} arg=${arg} font=${fontScale} total=${res.total} ms=${Math.round(res.ms)}`)
    log(`count_results ${JSON.stringify(res.results)}`)
}
// Visible paginator page count for one section (agreement check).
window.sampleVisible = async index => {
    await view.goTo(index)
    await new Promise(r => setTimeout(r, 400))
    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))
    log(`visible_pages index=${index} pages=${view.renderer.pages - 2} counted=${window.__lastCounts?.[index]}`)
}
window.logCfi = () => log(`cfi index=${view.lastLocation?.index ?? view.renderer.getContents()[0]?.index} page=${view.renderer.page} ${view.lastLocation?.cfi}`) // SPIKE S2
window.forgetCounts = () => { window.__lastCounts = null; log('counts_forgotten') }
window.sampleMany = async list => { for (const i of list) await window.sampleVisible(i); log('sample_done') }
// Text length and block-image count per section, from the DOM (for the estimator).
window.sectionStats = async () => {
    const out = []
    for (const s of book.sections) {
        if (s.linear === 'no' || !s.createDocument) { out.push([0, 0]); continue }
        const d = await s.createDocument()
        out.push([d.body?.textContent.length ?? 0, d.body?.querySelectorAll('img, svg').length ?? 0])
    }
    window.__chars = out.map(x => x[0])
    log(`section_stats ${JSON.stringify(out)}`)
}

window.addEventListener('error', e => log('onerror ' + e.message))
window.addEventListener('unhandledrejection', e => log('rejection ' + (e.reason?.stack ?? e.reason)))
A.hostReady()
