/**
 * C-089: the first page replaces the reader's loading screen directly. `load` means a section's images have arrived, not
 * that they are decoded, and the compositor shows nothing new until every tile of the frame is ready, text included. So
 * the first `painted` waits until the images on the page are decoded, for at most [FIRST_PAINT_WAIT_MS].
 */
export const FIRST_PAINT_WAIT_MS = 400

type Decodable = { decode(): Promise<void> }

/** The `img` and SVG `image` elements of [contents] that are at least partly inside the [width] x [height] host viewport. */
export function pageImages(contents: { doc?: Document }[], width: number, height: number): Decodable[] {
  const out: Decodable[] = []
  for (const { doc } of contents) {
    const box = (doc?.defaultView?.frameElement as HTMLElement | null | undefined)?.getBoundingClientRect()
    for (const el of doc?.querySelectorAll('img, image') ?? []) {
      const r = el.getBoundingClientRect()
      const x = (box?.left ?? 0) + r.left, y = (box?.top ?? 0) + r.top
      if (r.width > 0 && r.height > 0 && x < width && y < height && x + r.width > 0 && y + r.height > 0 && 'decode' in el) out.push(el as unknown as Decodable)
    }
  }
  return out
}

/** Resolves when every image is decoded or has failed to, or after [ms]: a broken or slow image never holds the page back. */
export function decoded(images: Decodable[], ms = FIRST_PAINT_WAIT_MS): Promise<void> {
  return new Promise(done => {
    const timer = setTimeout(done, ms)
    void Promise.allSettled(images.map(i => i.decode())).then(() => { clearTimeout(timer); done() })
  })
}
