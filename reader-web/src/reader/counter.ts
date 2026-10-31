import { applySettings, type WebSettings } from './styles'

/**
 * Page counter (F-034, P-021; design: docs/spikes/S1-page-totals.md). Sections are laid out hidden, in batches, with the
 * paginator's exact column CSS, the same injected style sheet and the same settings, and their pages counted the way
 * foliate's View counts them. The first batch is a stratified sample (for the "≈ N" estimate); the rest follows in
 * reading order. Runs on the main thread between tasks; a newer layout generation cancels it.
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

export type Layout = { width: number; height: number; gap: number; columnWidth: number; margin: number }
export type CountMessage = { gen: number; linear?: number[]; counts: [number, number][]; sample: boolean; done: boolean }

type Section = { load(): Promise<string>; unload?(): void; linear?: string }
type Renderer = { pagedLayout: Layout; styles: string | [string, string] | undefined; busy: boolean }

export const SAMPLE = 12
export const BATCH = 24

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
const imagesSettled = (doc: Document) => Promise.all([...doc.images].filter(i => !i.complete)
  .map(i => new Promise(r => { i.addEventListener('load', r, { once: true }); i.addEventListener('error', r, { once: true }) })))

export class PageCounter {
  private gen = 0
  private host: HTMLElement | null = null
  private lang: string
  private dir: string

  constructor(
    private sections: Section[],
    private renderer: () => Renderer | null,
    private settings: () => WebSettings,
    lang: string,
    dir: string,
    private send: (m: CountMessage) => void,
  ) {
    this.lang = /^[A-Za-z0-9-]*$/.test(lang) ? lang : ''
    this.dir = dir === 'rtl' ? 'rtl' : 'ltr'
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
    for (let at = 0; at < order.length; ) {
      if (!live()) return
      const size = at === 0 ? sample.length : BATCH
      const batch = order.slice(at, at + size)
      while (this.renderer()?.busy) { await sleep(40); if (!live()) return } // never during a page turn
      const counts = await this.countBatch(batch, live)
      if (!counts || !live()) return
      at += size
      this.send({ gen, counts, sample: at === sample.length && sample.length > 0, done: at >= order.length })
      await sleep(0) // let input and painting run between batches
    }
  }

  /** One hidden document holding every section of [batch] in its own column box. */
  private async countBatch(batch: number[], live: () => boolean): Promise<[number, number][] | null> {
    const r = this.renderer()
    if (!r) return null
    const layout = r.pagedLayout
    if (!layout || !(layout.width > 0 && layout.height > 0)) return null // no viewport yet: nothing to count
    const styles = Array.isArray(r.styles) ? r.styles : ['', r.styles ?? '']
    const { width, height, gap, columnWidth, margin } = layout
    const docs = await Promise.all(batch.map(async i => {
      try {
        const url = await this.sections[i].load()
        const res = await fetch(url)
        const type = res.headers.get('content-type') || 'application/xhtml+xml'
        const text = await res.text()
        let d = new DOMParser().parseFromString(text, /xml/.test(type) ? 'application/xhtml+xml' : 'text/html')
        if (d.querySelector('parsererror')) d = new DOMParser().parseFromString(text, 'text/html')
        return d
      } catch { return null }
    }))
    if (!live()) { batch.forEach(i => this.sections[i].unload?.()); return null }
    const heads = new Set<string>()
    for (const d of docs) for (const el of d?.querySelectorAll('head link[rel~=stylesheet], head style') ?? []) heads.add(el.outerHTML)
    const boxes = docs.map((d, k) => `<div class="er-box" data-i="${batch[k]}">${d?.body?.innerHTML ?? ''}</div>`)
    const html = `<!doctype html><html lang="${this.lang}" dir="${this.dir}"><head><meta charset="utf-8">
<style>${styles[0]}</style>${[...heads].join('')}<style>${styles[1]}</style><style>
html,body{margin:0!important;padding:0!important}
.er-box{box-sizing:border-box!important;width:${width}px!important;height:${height}px!important;
 column-width:${Math.trunc(columnWidth)}px!important;column-gap:${gap}px!important;column-fill:auto!important;
 padding:0 ${gap / 2}px!important;overflow:hidden!important;overflow-wrap:break-word!important;
 -webkit-line-box-contain:block glyphs replaced}
.er-box :is(img,svg,video){max-height:${height - margin * 2}px!important;max-width:100%;object-fit:contain!important;
 break-inside:avoid!important;box-sizing:border-box!important}
</style></head><body>${boxes.join('')}</body></html>`
    const frame = this.frame(width, height)
    const url = URL.createObjectURL(new Blob([html], { type: 'text/html' }))
    try {
      await new Promise<void>(res => { frame.onload = () => res(); frame.src = url })
      const doc = frame.contentDocument
      if (!doc || !live()) return null
      applySettings(doc, this.settings())
      await Promise.all([doc.fonts?.ready, imagesSettled(doc)])
      if (!live()) return null
      const range = doc.createRange()
      const out: [number, number][] = []
      for (const box of doc.querySelectorAll<HTMLElement>('.er-box')) {
        range.selectNodeContents(box)
        const c = range.getBoundingClientRect()
        const b = box.getBoundingClientRect()
        out.push([Number(box.dataset.i), Math.max(1, Math.ceil((c.right - b.left) / width))])
      }
      return out
    } finally {
      URL.revokeObjectURL(url)
      batch.forEach(i => this.sections[i].unload?.())
    }
  }

  private frame(width: number, height: number): HTMLIFrameElement {
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
