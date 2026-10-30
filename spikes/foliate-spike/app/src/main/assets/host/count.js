// SPIKE (M0 S1): hidden-layout page counter for P-021.
//
// Strategies:
//  - 'sequential' / 'pipelined': lay out each section in foliate's own `View` (exported by the spike patch of
//    paginator.js) inside an invisible host of the same size as the paginator container, with the same layout
//    object (`renderer.layout`) and the same injected styles. 'pipelined' runs `lanes` views concurrently.
//    Count = (view element width / page width) - 2, exactly what `Paginator.pages - 2` reports.
//  - 'batched': one document per batch of sections; each section body in its own multi-column box with the
//    same column CSS. Changes the document structure, so counts can disagree (measured, not fudged).
import { View } from '../foliate-js/paginator.js'

const nextFrame = () => new Promise(r => requestAnimationFrame(() => r()))
const yieldTask = () => new Promise(r => setTimeout(r, 0))

const makeHost = (w, h) => {
    const d = document.createElement('div')
    Object.assign(d.style, {
        position: 'fixed', left: '-20000px', top: '0', width: w + 'px', height: h + 'px',
        visibility: 'hidden', overflow: 'hidden', pointerEvents: 'none', contain: 'strict',
    })
    document.body.append(d)
    return d
}

const injectStyles = (doc, styles) => {
    if (!doc.head) return
    const before = doc.createElement('style')
    const after = doc.createElement('style')
    doc.head.prepend(before)
    doc.head.append(after)
    if (Array.isArray(styles)) { before.textContent = styles[0] ?? ''; after.textContent = styles[1] ?? '' }
    else after.textContent = styles ?? ''
}

const imagesSettled = doc => Promise.all([...doc.images].filter(i => !i.complete)
    .map(i => new Promise(r => { i.addEventListener('load', r, { once: true }); i.addEventListener('error', r, { once: true }) })))

// One lane: a reusable View in its own hidden host.
const makeLane = (layout, prepare) => {
    const host = makeHost(layout.width, layout.height)
    let view = null
    return {
        async count(section) {
            const src = await section.load()
            if (!src) return 0
            view?.destroy()
            view = new View({ container: host, onExpand: () => {} })
            host.replaceChildren(view.element)
            await view.load(src, doc => prepare(doc), () => layout)
            const doc = view.document
            await Promise.all([doc.fonts?.ready, imagesSettled(doc)])
            view.expand()
            const pages = Math.round(view.element.getBoundingClientRect().width / layout.width) - 2
            section.unload?.()
            return Math.max(1, pages)
        },
        dispose() { view?.destroy(); host.remove() },
    }
}

export async function countAll(book, { strategy = 'sequential', lanes = 2, batch = 24, layout, styles, lang, dir,
    stratified = false }, onSection, onBatch) {
    let firstBatch = null
    const t0 = performance.now()
    const n = book.sections.length
    const results = new Array(n).fill(0)
    const prepare = doc => {
        doc.documentElement.lang ||= lang ?? ''
        if (dir) doc.documentElement.dir ||= dir
        injectStyles(doc, styles)
    }
    let linear = [...book.sections.keys()].filter(i => book.sections[i].linear !== 'no' && book.sections[i].load)
    // 'stratified': the first batch is `batch` sections spread evenly over the book, so an estimate
    // (scaled from those sections) is available after one batch; the rest follow in reading order.
    if (stratified) {
        const k0 = typeof stratified === 'number' ? stratified : batch
        const step = linear.length / k0
        const sample = [...new Set(Array.from({ length: k0 }, (_, k) => linear[Math.floor(k * step + step / 2)]))]
        const set = new Set(sample)
        linear = [...sample, ...linear.filter(i => !set.has(i))]
        firstBatch = sample
    }
    if (strategy === 'sequential' || strategy === 'pipelined') {
        const k = strategy === 'pipelined' ? lanes : 1
        const ls = Array.from({ length: k }, () => makeLane(layout, prepare))
        let next = 0
        await Promise.all(ls.map(async lane => {
            while (next < linear.length) {
                const i = linear[next++]
                try { results[i] = await lane.count(book.sections[i]) }
                catch (e) { results[i] = 0; console.warn('count failed', i, e) }
                onSection?.(i, results[i])
                await yieldTask()
            }
        }))
        ls.forEach(l => l.dispose())
    } else if (strategy === 'batched') {
        const { width, height, gap, columnWidth, margin } = layout
        const host = makeHost(width, height)
        const frame = document.createElement('iframe')
        frame.setAttribute('sandbox', 'allow-same-origin')
        Object.assign(frame.style, { width: width + 'px', height: height + 'px', border: '0' })
        host.append(frame)
        const userStyles = Array.isArray(styles) ? styles : ['', styles ?? '']
        for (let s = 0, size; s < linear.length; s += size) {
            size = s === 0 && firstBatch ? firstBatch.length : batch
            const idx = linear.slice(s, s + size)
            const srcs = await Promise.all(idx.map(i => book.sections[i].load()))
            const docs = await Promise.all(srcs.map(async u => {
                const r = await fetch(u)
                const type = r.headers.get('content-type') || 'application/xhtml+xml'
                const text = await r.text()
                let d = new DOMParser().parseFromString(text, /xml/.test(type) ? 'application/xhtml+xml' : 'text/html')
                if (d.querySelector('parsererror')) d = new DOMParser().parseFromString(text, 'text/html')
                return d
            }))
            const heads = new Set()
            for (const d of docs) for (const el of d.querySelectorAll('head link[rel~=stylesheet], head style'))
                heads.add(el.outerHTML)
            const boxes = docs.map((d, k) => `<div class="er-box" data-i="${idx[k]}">${d.body?.innerHTML ?? ''}</div>`)
            const html = `<!doctype html><html lang="${lang ?? ''}" dir="${dir ?? ''}"><head><meta charset="utf-8">
<style>${userStyles[0]}</style>${[...heads].join('')}<style>${userStyles[1]}</style><style>
html,body{margin:0!important;padding:0!important}
.er-box{box-sizing:border-box!important;width:${width}px!important;height:${height}px!important;
 column-width:${Math.trunc(columnWidth)}px!important;column-gap:${gap}px!important;column-fill:auto!important;
 padding:0 ${gap / 2}px!important;overflow:hidden!important;overflow-wrap:break-word!important;
 -webkit-line-box-contain:block glyphs replaced}
.er-box :is(img,svg,video){max-height:${height - margin * 2}px!important;max-width:100%;object-fit:contain!important;
 break-inside:avoid!important;box-sizing:border-box!important}
</style></head><body>${boxes.join('')}</body></html>`
            const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
            await new Promise(r => { frame.onload = r; frame.src = url })
            const doc = frame.contentDocument
            await Promise.all([doc.fonts?.ready, imagesSettled(doc)])
            const range = doc.createRange()
            for (const box of doc.querySelectorAll('.er-box')) {
                range.selectNodeContents(box)
                const c = range.getBoundingClientRect()
                const b = box.getBoundingClientRect()
                const i = +box.dataset.i
                results[i] = Math.max(1, Math.ceil((c.right - b.left) / width))
                onSection?.(i, results[i])
            }
            URL.revokeObjectURL(url)
            idx.forEach(i => book.sections[i].unload?.())
            onBatch?.(idx, results, performance.now() - t0, s === 0 ? firstBatch : null)
            await yieldTask()
        }
        host.remove()
    }
    return { total: results.reduce((a, b) => a + b, 0), results, ms: performance.now() - t0 }
}
