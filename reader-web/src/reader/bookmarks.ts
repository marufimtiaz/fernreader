import * as CFI from '../../vendor/foliate-js/epubcfi.js'

const point = (cfi: string, toEnd = false): any | null => {
  try {
    const p = (CFI as any).collapse((CFI as any).parse(cfi), toEnd)
    return Array.isArray(p) && p.length ? p : null
  } catch { return null }
}

/**
 * F-041: which of [bookmarks] (CFI strings, virtual coordinates) lie on the page whose location is [page] (foliate's
 * relocate CFI: a range CFI from the first to the last visible position). A bookmark counts by its own start; it is on
 * the page when start <= it < end (the end belongs to the next page, so a bookmark is never on two pages). A page
 * reported as a point matches only a bookmark at that point. O(bookmarks), pure compares on parsed CFIs, no layout.
 * Returns the matching strings as given, so the app maps them back without indices that could go stale.
 */
export function visibleBookmarks(bookmarks: readonly string[], page: string): string[] {
  const start = page ? point(page) : null
  const end = page ? point(page, true) : null
  if (!start || !end || !bookmarks.length) return []
  const cmp = (CFI as any).compare as (a: unknown, b: unknown) => number
  const isPoint = cmp(start, end) === 0
  const out: string[] = []
  for (const b of bookmarks) {
    const p = b ? point(b) : null
    if (!p) continue
    try {
      if (cmp(p, start) >= 0 && (isPoint ? cmp(p, end) === 0 : cmp(p, end) < 0)) out.push(b)
    } catch { /* an unusable CFI marks nothing */ }
  }
  return out
}
