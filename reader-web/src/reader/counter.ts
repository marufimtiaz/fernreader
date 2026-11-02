import { View } from '../../vendor/foliate-js/paginator.js'
import { applySettings, markWrappers, type WebSettings } from './styles'
import { polyfillMfenced } from './mathml'

/**
 * Page counter (F-034, P-021; design: docs/spikes/S1-page-totals.md). Sections are laid out hidden, in batches, with the
 * paginator's exact column CSS, the same injected style sheet and the same settings, and their pages counted the way
 * foliate's View counts them. The first batch is a stratified sample (for the "≈ N" estimate); the rest follows in
 * reading order. Each shared batch is self-checked against one section counted alone (C-003, [selfCheck]). Runs on the
 * main thread between tasks; a newer layout generation cancels it.
 */

/** [k] linear sections spread evenly over the book (the middle of each of k equal strata), in reading order. */
export function stratifiedSample(linear: number[], k: number): number[] {
  if (linear.length <= k) return [...linear]
  const step = linear.length / k
  return [...new Set(Array.from({ length: k }, (_, j) => linear[Math.floor(j * step + step / 2)]))]
}

/** The sample first, then every other linear section once, in reading order. */
export function readingOrder(linear: number[], sample: number[]): number[] {
  const s = new Set(sample)
  return [...sample, ...linear.filter(i => !s.has(i))]
}

/**
 * Which sections of a batch share the hidden document: those with the batch's most common [key] (head style sheets plus
 * `html`/`body` attributes, which the batch document copies). Others are counted alone in a foliate View, exact by
 * construction (S1 "Risks"). A null key (unreadable document) is counted alone. A key no other section shares is
 * counted alone too: a one-section document would be laid out a second time by the C-003 self-check (the 10 MB
 * chapter: twice the time and two such layouts in memory at once).
 */
export function planBatch(metas: { index: number; key: string | null }[]): { key: string | null; batched: number[]; alone: number[] } {
  const freq = new Map<string, number>()
  for (const m of metas) if (m.key != null) freq.set(m.key, (freq.get(m.key) ?? 0) + 1)
  let key: string | null = null
  for (const [k, n] of freq) if (key == null || n > freq.get(key)!) key = k
  const shared = key != null && freq.get(key)! > 1
  return {
    key,
    batched: metas.filter(m => shared && m.key === key).map(m => m.index),
    alone: metas.filter(m => !shared || m.key !== key).map(m => m.index),
  }
}

/**
 * C-003 self-check: the section of a shared batch that is also counted alone in a foliate View. The largest by markup
 * [size] (unknown = 0; ties: the first): a layout difference between the batch document and the paginator grows with
 * the number of pages, so the longest section shows it first, and checking one section costs at most one alone count
 * per batch (≤ 1/24 of a full batch counted alone).
 */
export function checkSection(batched: number[], size: (i: number) => number | undefined): number {
  let best = batched[0]
  for (const i of batched) if ((size(i) ?? 0) > (size(best) ?? 0)) best = i
  return best
}

/**
 * C-003: the batch's [counted] pages against the [check] section's alone count. They agree: keep the batch counts.
 * They differ: the batch document does not reproduce the paginator for this book, so keep only the alone count and
 * [recount] alone the batch's other sections and the sections [earlier] batches of this layout counted in a batch
 * document (their checks passed, but the difference may show in other sections: jlreq); the caller counts the rest of
 * the book alone.
 */
export function selfCheck(counted: [number, number][], check: [number, number], earlier: number[]): { mismatch: boolean; keep: [number, number][]; recount: number[] } {
  const [ci, alone] = check
  const batch = counted.find(([i]) => i === ci)?.[1]
  if (batch === alone) return { mismatch: false, keep: counted, recount: [] }
  return { mismatch: true, keep: [check], recount: [...counted.map(([i]) => i).filter(i => i !== ci), ...earlier] }
}

/**
 * Pages of one column box from its content's bounding rect: columns run right for left-to-right text and left for
 * right-to-left text, so the content extends past the box on that side (same count as foliate's View.expand).
 */
export function boxPages(content: { left: number; right: number }, box: { left: number; right: number }, width: number): number {
  const extent = Math.max(content.right - box.left, box.right - content.left)
  return Math.max(1, Math.ceil(extent / width))
}

/** Attribute text of a start tag, without namespace declarations, normalised to single spaces. */
const tagAttrs = (raw: string) => raw.replace(/\/\s*$/, '').trim().split(/\s+(?=[\w:-]+\s*=|[\w:-]+(?:\s|$))/)
  .filter(a => a && !/^xmlns(:|=|$)/.test(a)).join(' ')

export type Source = { head: string[]; htmlAttrs: string; bodyAttrs: string; body: string }

/**
 * The parts of a section's markup the batch document needs, cut from the text (no DOM is built: the batch document
 * parses the markup once, and the counter stays light on memory, P-060). Null without a body element.
 */
export function splitSource(text: string): Source | null {
  const body = /<body\b([^>]*)>([\s\S]*)<\/body\s*>/i.exec(text)
  if (!body) return null
  const html = /<html\b([^>]*)>/i.exec(text)
  const headText = /<head\b[^>]*>([\s\S]*?)<\/head\s*>/i.exec(text)?.[1] ?? ''
  const head = [...headText.matchAll(/<link\b[^>]*>|<style\b[\s\S]*?<\/style\s*>/gi)].map(m => m[0])
    .filter(t => !/^<link/i.test(t) || /\brel\s*=\s*["'][^"']*\bstylesheet\b/i.test(t))
  return { head, htmlAttrs: tagAttrs(html?.[1] ?? ''), bodyAttrs: tagAttrs(body[1]), body: body[2] }
}

export type Layout = { width: number; height: number; gap: number; columnWidth: number; margin: number; verticalPages?: boolean }
export type CountMessage = { gen: number; linear?: number[]; counts: [number, number][]; sample: boolean; done: boolean }

type Section = { load(): Promise<string>; unload?(): void; linear?: string; size?: number }
type Renderer = { pagedLayout: Layout; styles: string | [string, string] | undefined; busy: boolean }

export const SAMPLE = 12
export const BATCH = 24
/**
 * Markup per batch document (C-062). A batch of 24 chunk parts (~128 K characters each) built a ~3 MB hidden document,
 * and the renderer kept ~300 MB more for ~30 s after counting the huge chapter (P-061 peak 629 MB). Ordinary chapters
 * (War and Peace: ~9 KB average) still go 24 at a time.
 */
export const BATCH_BYTES = 512 * 1024

/**
 * [order] cut into batch documents: the first [sampleLen] (the sample) and the rest never share one; each holds at most
 * [BATCH] sections and, past its first section, at most [BATCH_BYTES] of markup by [size] (unknown = small).
 */
export function batches(order: number[], sampleLen: number, size: (i: number) => number | undefined): number[][] {
  const out: number[][] = []
  let cur: number[] = [], bytes = 0
  order.forEach((i, k) => {
    const b = size(i) ?? 0
    if (cur.length && (k === sampleLen || cur.length >= BATCH || bytes + b > BATCH_BYTES)) { out.push(cur); cur = []; bytes = 0 }
    cur.push(i); bytes += b
  })
  if (cur.length) out.push(cur)
  return out
}

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
const imagesSettled = (doc: Document) => Promise.all([...doc.images].filter(i => !i.complete)
  .map(i => new Promise(r => { i.addEventListener('load', r, { once: true }); i.addEventListener('error', r, { once: true }) })))

export class PageCounter {
  private gen = 0
  private host: HTMLElement | null = null
  private lang: string
  private dir: string
  /**
   * C-003: a self-check found that this book's batch documents count differently from the paginator; every later
   * count of this book (this and later layout generations) counts each section alone.
   */
  private bookAlone = false

  constructor(
    private sections: Section[],
    private renderer: () => Renderer | null,
    private settings: () => WebSettings,
    lang: string,
    dir: string,
    private send: (m: CountMessage) => void,
    /**
     * [alone]: every section is counted alone in a foliate View, one at a time (C-032: vertical-rl books, whose pages
     * the batch document's horizontal column boxes cannot reproduce). An empty [dir] sets no `dir` (CJK, as view.js).
     */
    private opts: { alone?: boolean } = {},
  ) {
    this.lang = /^[A-Za-z0-9-]*$/.test(lang) ? lang : ''
    this.dir = dir === 'rtl' ? 'rtl' : dir === '' ? '' : 'ltr'
  }

  /** Stops the running count (a newer layout, or the book closed). */
  stop() { this.gen++; this.host?.remove(); this.host = null }

  private linear() { return this.sections.map((s, i) => (s.linear !== 'no' ? i : -1)).filter(i => i >= 0) }

  /**
   * A new layout generation exists from now on (the count itself starts a little later, debounced): the app drops the
   * old counts at once instead of mixing them with the new layout's visible pages.
   */
  announce(gen: number) {
    this.stop()
    this.gen = gen
    this.send({ gen, linear: this.linear(), counts: [], sample: false, done: false })
  }

  /** Starts counting for layout generation [gen]; the previous count stops. */
  async start(gen: number) {
    this.stop()
    this.gen = gen
    const live = () => this.gen === gen
    const linear = this.linear()
    this.send({ gen, linear, counts: [], sample: false, done: false })
    const sample = stratifiedSample(linear, SAMPLE)
    const order = readingOrder(linear, sample)
    let at = 0
    let pending: [number, number][] = [] // the sample's counts, sent together (the estimate needs all of them)
    const viaBatch: number[] = [] // sections this layout counted in a batch document (C-003: recounted on a mismatch)
    for (const batch of batches(order, sample.length, i => this.sections[i]?.size)) {
      if (!live()) return
      while (this.renderer()?.busy) { await sleep(40); if (!live()) return } // never during a page turn
      const counts = this.opts.alone || this.bookAlone ? await this.countEachAlone(batch, live) : await this.countBatch(batch, live, viaBatch)
      if (!counts || !live()) return
      at += batch.length
      if (at < sample.length) pending.push(...counts)
      else {
        this.send({ gen, counts: [...pending, ...counts], sample: at === sample.length && sample.length > 0, done: at >= order.length })
        pending = []
      }
      if (at >= order.length) { this.host?.remove(); this.host = null} // the last batch document goes too
      await sleep(0) // let input and painting run between batches
    }
  }

  /** Each section of [batch] in its own View, yielding and waiting out page turns between sections. */
  private async countEachAlone(batch: number[], live: () => boolean): Promise<[number, number][] | null> {
    const out: [number, number][] = []
    for (const i of batch) {
      while (this.renderer()?.busy) { await sleep(40); if (!live()) return null }
      const r = this.renderer()
      if (!r || !live()) return null
      const layout = r.pagedLayout
      if (!layout || !(layout.width > 0 && layout.height > 0)) return null
      out.push([i, await this.countAlone(i, r)])
      if (!live()) return null
      await sleep(0)
    }
    return out
  }

  /**
   * One hidden document holding every section of [batch] in its own column box, self-checked (C-003). [viaBatch]: the
   * sections earlier batches of this layout counted in a batch document; this batch's are added. On a mismatch they
   * are recounted alone and returned too (counts for sections outside [batch], overwriting the ones sent before).
   */
  private async countBatch(batch: number[], live: () => boolean, viaBatch: number[]): Promise<[number, number][] | null> {
    const r = this.renderer()
    if (!r) return null
    const layout = r.pagedLayout
    if (!layout || !(layout.width > 0 && layout.height > 0)) return null // no viewport yet: nothing to count
    // One section shares nothing: counted alone, without fetching its markup for a batch document (planBatch)
    if (batch.length === 1) return [[batch[0], await this.countAlone(batch[0], r)]]
    const docs = await Promise.all(batch.map(async i => {
      try { return splitSource(await (await fetch(await this.sections[i].load())).text()) } catch { return null }
    }))
    if (!live()) { batch.forEach(i => this.sections[i].unload?.()); return null }
    const metas = docs.map((d, k) => ({ index: batch[k], key: d ? JSON.stringify([d.head, d.htmlAttrs, d.bodyAttrs]) : null }))
    const plan = planBatch(metas)
    const out: [number, number][] = []
    try {
      if (plan.batched.length) {
        const counted = await this.layoutBatch(plan.batched.map(i => [i, docs[batch.indexOf(i)]!] as const), r, live)
        if (!counted) return null
        // C-003 self-check: one section of the batch also counted alone, as the paginator lays it out
        const ci = checkSection(plan.batched, i => this.sections[i]?.size)
        while (this.renderer()?.busy) { await sleep(40); if (!live()) return null }
        if (!live()) return null
        const check = selfCheck(counted, [ci, await this.countAlone(ci, r)], viaBatch)
        if (!live()) return null
        out.push(...check.keep)
        if (!check.mismatch) viaBatch.push(...plan.batched)
        else {
          this.bookAlone = true
          console.warn('page counter: batch count differs from the paginator, counting this book alone', ci)
          const rest = await this.countEachAlone(check.recount, live)
          if (!rest) return null
          out.push(...rest)
        }
      }
      for (const i of plan.alone) {
        if (!live()) return null
        out.push([i, await this.countAlone(i, r)])
      }
      return out
    } finally {
      batch.forEach(i => this.sections[i].unload?.())
    }
  }

  /** Sections that share head style sheets and html/body attributes, each in a column box of one hidden document. */
  private async layoutBatch(items: (readonly [number, Source])[], r: Renderer, live: () => boolean): Promise<[number, number][] | null> {
    const first = items[0][1]
    const { width, height, gap, columnWidth, margin } = r.pagedLayout
    const styles = Array.isArray(r.styles) ? r.styles : ['', r.styles ?? '']
    const htmlAttrs = first.htmlAttrs || `lang="${this.lang}"${this.dir ? ` dir="${this.dir}"` : ''}`
    const boxes = items.map(([i, d]) => `<div class="er-box" data-i="${i}">${d.body}</div>`)
    const html = `<!doctype html><html ${htmlAttrs}><head><meta charset="utf-8">
<style>${styles[0]}</style>${first.head.join('')}<style>${styles[1]}</style><style>
html,body{margin:0!important;padding:0!important}
.er-box{box-sizing:border-box!important;width:${width}px!important;height:${height}px!important;
 column-width:${Math.trunc(columnWidth)}px!important;column-gap:${gap}px!important;column-fill:auto!important;
 padding:0 ${gap / 2}px!important;overflow:hidden!important;overflow-wrap:break-word!important;
 -webkit-line-box-contain:block glyphs replaced}
.er-box :is(img,svg,video){max-height:${height - margin * 2}px!important;max-width:100%;object-fit:contain!important;
 break-inside:avoid!important;box-sizing:border-box!important}
</style></head><body ${first.bodyAttrs}>${boxes.join('')}</body></html>`
    const frame = this.frame(width, height)
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
    try {
      await new Promise<void>(res => { frame.onload = () => res(); frame.src = url })
      const doc = frame.contentDocument
      if (!doc || !live()) return null
      polyfillMfenced(doc) // as the shown document gets it (reader.ts load), before layout
      applySettings(doc, this.settings())
      // C-082: each box holds one section's body, so its whole-content wrappers lose their side margins as on screen
      for (const box of doc.querySelectorAll('.er-box')) markWrappers(doc, box)
      await Promise.all([doc.fonts?.ready, imagesSettled(doc)])
      if (!live()) return null
      const range = doc.createRange()
      const out: [number, number][] = []
      for (const box of doc.querySelectorAll<HTMLElement>('.er-box')) {
        range.selectNodeContents(box)
        const c = range.getBoundingClientRect()
        const b = box.getBoundingClientRect()
        out.push([Number(box.dataset.i), boxPages(c, b, width)])
      }
      return out
    } finally {
      URL.revokeObjectURL(url)
    }
  }

  /** One section in its own foliate View with the paginator's layout: what the visible paginator would count (C-003). */
  private async countAlone(i: number, r: Renderer): Promise<number> {
    const layout = r.pagedLayout
    const host = document.createElement('div')
    Object.assign(host.style, {
      position: 'fixed', left: '-20000px', top: '0', width: `${layout.width}px`, height: `${layout.height}px`,
      visibility: 'hidden', overflow: 'hidden', pointerEvents: 'none', contain: 'strict',
    })
    document.body.append(host)
    const view = new View({ container: host, onExpand: () => {} })
    host.append(view.element)
    const styles = Array.isArray(r.styles) ? r.styles : ['', r.styles ?? '']
    try {
      const src = await this.sections[i].load()
      await view.load(src, (doc: Document) => {
        doc.documentElement.lang ||= this.lang
        if (this.dir) doc.documentElement.dir ||= this.dir
        polyfillMfenced(doc)
        if (doc.head) {
          const before = doc.createElement('style'); before.textContent = styles[0]; doc.head.prepend(before)
          const after = doc.createElement('style'); after.textContent = styles[1]; doc.head.append(after)
        }
        applySettings(doc, this.settings())
      }, () => layout)
      const doc = view.document as Document
      await Promise.all([doc.fonts?.ready, imagesSettled(doc)])
      view.expand()
      return Math.max(1, Math.round(view.element.getBoundingClientRect().width / layout.width) - 2)
    } catch (e) {
      console.warn('count alone failed', i, e)
      return 1
    } finally {
      view.destroy(); host.remove()
      this.sections[i].unload?.()
    }
  }

  /** One hidden frame per count, navigated per batch: each navigation tears the previous batch document down. */
  private frame(width: number, height: number): HTMLIFrameElement {
    const old = this.host?.querySelector('iframe')
    if (old && this.host!.style.width === `${width}px` && this.host!.style.height === `${height}px`) return old
    this.host?.remove()
    const host = document.createElement('div')
    Object.assign(host.style, {
      position: 'fixed', left: '-20000px', top: '0', width: `${width}px`, height: `${height}px`,
      visibility: 'hidden', overflow: 'hidden', pointerEvents: 'none', contain: 'strict',
    })
    const frame = document.createElement('iframe')
    frame.setAttribute('sandbox', 'allow-same-origin')
    Object.assign(frame.style, { width: `${width}px`, height: `${height}px`, border: '0' })
    host.append(frame)
    document.body.append(host)
    this.host = host
    return frame
  }
}
