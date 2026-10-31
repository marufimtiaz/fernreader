import type { TocItem } from '../bridge/protocol'

type RawItem = { label?: string; href?: string; subitems?: RawItem[] }
type BookLike = { toc?: RawItem[]; sections: { id?: unknown }[]; resolveHref(href: string): { index: number } | null }

/** R-001: a TOC target is missing if it resolves to no spine section, or the section's file is not in the zip. */
function isMissing(book: BookLike, href: string, has: (name: string) => boolean): boolean {
  if (!href) return true
  let index: number | undefined
  try { index = book.resolveHref(href)?.index } catch { return true }
  if (index == null || index < 0) return true
  const id = book.sections[index]?.id // foliate-js: a section's id is its resource path
  return typeof id === 'string' && !has(id)
}

export function flattenToc(book: BookLike, has: (name: string) => boolean): TocItem[] {
  const out: TocItem[] = []
  const walk = (items: RawItem[] | undefined, depth: number) => {
    for (const i of items ?? []) {
      const href = i.href ?? ''
      out.push({ label: (i.label ?? '').trim(), href, depth, missing: isMissing(book, href, has) })
      walk(i.subitems, depth + 1)
    }
  }
  walk(book.toc, 0)
  return out
}
