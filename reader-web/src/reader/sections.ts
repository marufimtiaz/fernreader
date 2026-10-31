type Section = { load?: () => Promise<string> | string; unload?: () => void }

/**
 * One reference count per section for everyone who loads it: the paginator (visible and preloaded views) and the page
 * counter. foliate's resource loader counts a top-level section only once however often it is loaded, so the
 * counter's load/unload would otherwise revoke the blob URL (and the style sheets, images and fonts under it) of a
 * section that is on screen or preloaded. The real unload runs when the last user lets go, and only after the real
 * load has finished (a section evicted while it was still loading is released, not leaked). Wraps in place.
 */
export function shareSections(sections: Section[]) {
  for (const s of sections) {
    if (!s.load || (s as any).__real) continue
    const real = { load: s.load, unload: s.unload ?? (() => {}) }
    ;(s as any).__real = real
    let users = 0
    let url: Promise<string> | null = null
    let released: Promise<unknown> = Promise.resolve()
    s.load = () => {
      users++
      if (!url) {
        const p = released.then(() => real.load!.call(s))
        url = p
        p.catch(() => { if (url === p) url = null })
      }
      return url
    }
    s.unload = () => {
      if (users === 0) return
      if (--users > 0) return
      const p = url
      url = null
      if (p) released = p.then(() => real.unload.call(s), () => {})
    }
  }
}
