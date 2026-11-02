import type { TocItem } from '../bridge/protocol'

/**
 * C-085 "Chapters: One at a time" in scroll mode: the label of the control below a chapter's last line ("Next: <title>")
 * and above its first line ("Previous: <title>"). The texts come from the app (Copy.kt, sent with `open`); `{title}` is
 * replaced by the chapter's title. The defaults are only for a host that sends none (bench, tests).
 */
export type ChapterTexts = { nextChapter: string; previousChapter: string; nextChapterUntitled: string; previousChapterUntitled: string }

export const DEFAULT_CHAPTER_TEXTS: ChapterTexts = {
  nextChapter: 'Next: {title}', previousChapter: 'Previous: {title}', nextChapterUntitled: 'Next chapter', previousChapterUntitled: 'Previous chapter',
}

/**
 * The title of spine section [index]: its first contents entry, or, for a section without one (a chapter continued in
 * another file), the last entry before it; null when there is none.
 */
export function sectionTitle(toc: readonly TocItem[], index: number): string | null {
  let before: string | null = null
  let beforeIndex = -1
  for (const t of toc) {
    const label = t.label.trim()
    if (!label || t.index < 0) continue
    if (t.index === index) return label
    if (t.index < index && t.index >= beforeIndex) { before = label; beforeIndex = t.index }
  }
  return before
}

/** The label of the control that opens section [index], the next chapter ([dir] 1) or the previous one (-1). */
export function chapterEdgeLabel(toc: readonly TocItem[], index: number, dir: 1 | -1, texts: ChapterTexts): string {
  const title = sectionTitle(toc, index)
  if (title == null) return dir > 0 ? texts.nextChapterUntitled : texts.previousChapterUntitled
  return (dir > 0 ? texts.nextChapter : texts.previousChapter).split('{title}').join(title)
}
