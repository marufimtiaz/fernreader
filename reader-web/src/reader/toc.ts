import type { TocItem } from '../bridge/protocol'

type RawItem = { label?: string; href?: string; subitems?: RawItem[] }
type BookLike = { toc?: RawItem[]; sections: { id?: unknown }[]; resolveHref(href: string): { index: number } | null }

/** R-001: a TOC target is missing if it resolves to no spine section, or the section's file is not in the zip. */
function isMissing(book: BookLike, href: string, index: number, has: (name: string) => boolean): boolean {
  if (!href || index < 0) return true
  const id = book.sections[index]?.id // foliate-js: a section's id is its resource path
  return typeof id === 'string' && !has(id)
}

/** The spine section an href points to, -1 if it resolves to none. */
function sectionOf(book: BookLike, href: string): number {
  if (!href) return -1
  try { const i = book.resolveHref(href)?.index; return i != null && i >= 0 ? i : -1 } catch { return -1 }
}

export function flattenToc(book: BookLike, has: (name: string) => boolean): TocItem[] {
  const out: TocItem[] = []
  const walk = (items: RawItem[] | undefined, depth: number) => {
    for (const i of items ?? []) {
      const href = i.href ?? ''
      const index = sectionOf(book, href)
      out.push({ label: (i.label ?? '').trim(), href, depth, missing: isMissing(book, href, index, has), index })
      walk(i.subitems, depth + 1)
    }
  }
  walk(book.toc, 0)
  return out
}
