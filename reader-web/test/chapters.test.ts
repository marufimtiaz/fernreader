import { describe, expect, it } from 'vitest'
import { pullStep, pullThreshold, pullTurn } from '../vendor/foliate-js/paginator.js'
import { chapterEdgeLabel, chapterSpan, DEFAULT_CHAPTER_TEXTS, sectionTitle } from '../src/reader/chapters'
import type { TocItem } from '../src/bridge/protocol'

// C-085 "Chapters: One at a time" (local paginator patch, PATCHES.md): a deliberate overscroll pull past a chapter's end
// (or above its top) opens the next (previous) chapter. Finger positions are screen CSS px, y grows downwards.
describe('pullStep / pullTurn: an overscroll pull counts from where the scroller hit its end during the gesture', () => {
  const start = { startFrom: null, endFrom: null }
  const T = 100

  it('a finger moving up at the end pulls toward the next chapter', () => {
    let p = pullStep(start, { y: 600, atStart: false, atEnd: true })
    p = pullStep(p, { y: 560, atStart: false, atEnd: true })
    expect(p.next).toBe(40)
    expect(pullTurn(p, T)).toBe(0) // not far enough yet
    p = pullStep(p, { y: 480, atStart: false, atEnd: true })
    expect(p.next).toBe(120)
    expect(pullTurn(p, T)).toBe(1)
  })

  it('the pull starts where the end was reached, not where the finger went down (a drag that runs into the end)', () => {
    let p = pullStep(start, { y: 900, atStart: false, atEnd: false }) // still scrolling the text
    p = pullStep(p, { y: 500, atStart: false, atEnd: true }) // the end is reached here
    p = pullStep(p, { y: 450, atStart: false, atEnd: true })
    expect(p.next).toBe(50)
    expect(pullTurn(p, T)).toBe(0)
  })

  it('leaving the end resets the pull (the finger went back down and the text scrolled up)', () => {
    let p = pullStep(start, { y: 600, atStart: false, atEnd: true })
    p = pullStep(p, { y: 450, atStart: false, atEnd: true })
    p = pullStep(p, { y: 520, atStart: false, atEnd: false })
    p = pullStep(p, { y: 500, atStart: false, atEnd: true })
    expect(p.next).toBe(0)
    expect(pullTurn(p, T)).toBe(0)
  })

  it('a finger moving down at the top pulls toward the previous chapter', () => {
    let p = pullStep(start, { y: 300, atStart: true, atEnd: false })
    p = pullStep(p, { y: 420, atStart: true, atEnd: false })
    expect(p.prev).toBe(120)
    expect(pullTurn(p, T)).toBe(-1)
  })

  it('a chapter shorter than the screen (top and end at once): the finger direction decides', () => {
    let p = pullStep(start, { y: 500, atStart: true, atEnd: true })
    p = pullStep(p, { y: 380, atStart: true, atEnd: true })
    expect(pullTurn(p, T)).toBe(1)
    p = pullStep(start, { y: 500, atStart: true, atEnd: true })
    p = pullStep(p, { y: 620, atStart: true, atEnd: true })
    expect(pullTurn(p, T)).toBe(-1)
  })

  it('moving the wrong way at an edge is no pull', () => {
    let p = pullStep(start, { y: 500, atStart: false, atEnd: true })
    p = pullStep(p, { y: 700, atStart: false, atEnd: true }) // down at the end: scrolls the text up, not a pull
    expect(pullTurn(p, T)).toBe(0)
  })

  it('the threshold is a fifth of the viewport, at least 96 CSS px', () => {
    expect(pullThreshold(800)).toBe(160)
    expect(pullThreshold(300)).toBe(96)
    expect(pullThreshold(0)).toBe(96)
  })
})

describe('chapter edge labels', () => {
  const toc: TocItem[] = [
    { label: 'Cover', href: 'c.xhtml', depth: 0, missing: false, index: 0 },
    { label: 'BOOK ONE', href: 'b1.xhtml', depth: 0, missing: false, index: 2 },
    { label: 'CHAPTER I', href: 'b1.xhtml#c1', depth: 1, missing: false, index: 2 },
    { label: 'CHAPTER II', href: 'c2.xhtml', depth: 1, missing: false, index: 3 },
    { label: 'Gone', href: 'x.xhtml', depth: 0, missing: true, index: -1 },
    { label: '  ', href: 'blank.xhtml', depth: 0, missing: false, index: 6 },
  ]

  it('a section with its own entries takes the first one', () => {
    expect(sectionTitle(toc, 2)).toBe('BOOK ONE')
    expect(sectionTitle(toc, 3)).toBe('CHAPTER II')
  })
  it('a section without an entry continues the chapter before it', () => {
    expect(sectionTitle(toc, 4)).toBe('CHAPTER II')
    expect(sectionTitle(toc, 1)).toBe('Cover')
  })
  it('no usable entry at or before the section: no title (blank labels are ignored)', () => {
    expect(sectionTitle([], 3)).toBeNull()
    expect(sectionTitle(toc.slice(1), 1)).toBeNull()
    expect(sectionTitle(toc, 6)).toBe('CHAPTER II')
  })

  it('"Next: <title>" / "Previous: <title>" from the app\'s texts, the untitled text without a title', () => {
    const texts = { nextChapter: 'Next: {title}', previousChapter: 'Previous: {title}', nextChapterUntitled: 'Next chapter', previousChapterUntitled: 'Previous chapter' }
    expect(chapterEdgeLabel(toc, 3, 1, texts)).toBe('Next: CHAPTER II')
    expect(chapterEdgeLabel(toc, 2, -1, texts)).toBe('Previous: BOOK ONE')
    expect(chapterEdgeLabel([], 2, 1, texts)).toBe('Next chapter')
    expect(chapterEdgeLabel([], 0, -1, texts)).toBe('Previous chapter')
  })
  it('a title with "$" or "{title}" in it is inserted as is', () => {
    const t = [{ label: 'Costs $& {title}', href: 'a', depth: 0, missing: false, index: 0 }]
    expect(chapterEdgeLabel(t, 0, 1, DEFAULT_CHAPTER_TEXTS)).toBe('Next: Costs $& {title}')
  })
})

// C-085 (gatekeeper): the parts of a huge document split by the app (M8) are one chapter in One at a time. The app sends
// the virtual sections that continue the one before ("continued"); a chapter is a run of such sections.
describe('chapterSpan: a chapter is a section and the parts that continue it', () => {
  const continued = new Set([3, 4, 5, 8]) // 2..5 are parts of one document, 7..8 of another
  it('a section that is not split is its own chapter', () => {
    expect(chapterSpan(continued, 10, 0)).toEqual([0, 0])
    expect(chapterSpan(continued, 10, 6)).toEqual([6, 6])
    expect(chapterSpan(continued, 10, 9)).toEqual([9, 9])
  })
  it('every part of a split document gives the whole run', () => {
    for (const i of [2, 3, 4, 5]) expect(chapterSpan(continued, 10, i)).toEqual([2, 5])
    for (const i of [7, 8]) expect(chapterSpan(continued, 10, i)).toEqual([7, 8])
  })
  it('no parts (an unsplit book) and the book\'s ends', () => {
    expect(chapterSpan(new Set(), 3, 1)).toEqual([1, 1])
    expect(chapterSpan(new Set([1, 2]), 3, 2)).toEqual([0, 2])
    expect(chapterSpan(new Set([0]), 2, 0)).toEqual([0, 0]) // a first section can't continue anything
  })
})
