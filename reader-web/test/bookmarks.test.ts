import { describe, expect, it } from 'vitest'
import { visibleBookmarks } from '../src/reader/bookmarks'

// A page whose visible range runs from /4/2/1:10 to /4/6/1:5 in section /6/4 (a range CFI, as foliate reports it).
const PAGE = 'epubcfi(/6/4!/4,/2/1:10,/6/1:5)'

describe('visibleBookmarks (F-041)', () => {
  it('lists the bookmarks inside the visible range, by their own strings', () => {
    const a = 'epubcfi(/6/4!/4/2/1:10)' // the page start itself
    const b = 'epubcfi(/6/4!/4/4/1:30)' // the middle
    const c = 'epubcfi(/6/4!/4/6/1:4)'  // just before the end
    expect(visibleBookmarks([a, b, c], PAGE)).toEqual([a, b, c])
  })
  it('excludes bookmarks before the start, at the end boundary (the next page owns it) and after', () => {
    const before = 'epubcfi(/6/4!/4/2/1:9)'
    const atEnd = 'epubcfi(/6/4!/4/6/1:5)'
    const after = 'epubcfi(/6/4!/4/8/1:0)'
    const otherSection = 'epubcfi(/6/6!/4/2/1:10)'
    const earlierSection = 'epubcfi(/6/2!/4/4/1:0)'
    expect(visibleBookmarks([before, atEnd, after, otherSection, earlierSection], PAGE)).toEqual([])
  })
  it('a bookmark stored as a range counts by its start', () => {
    const r = 'epubcfi(/6/4!/4,/4/1:30,/6/1:2)'
    expect(visibleBookmarks([r], PAGE)).toEqual([r])
  })
  it('a point location (no range) marks only an equal bookmark', () => {
    const here = 'epubcfi(/6/4!/4/2/1:10)'
    expect(visibleBookmarks([here, 'epubcfi(/6/4!/4/2/1:11)'], here)).toEqual([here])
  })
  it('ignores malformed CFIs and an empty page location', () => {
    expect(visibleBookmarks(['nonsense', '', 'epubcfi(/6/4!/4/4/1:30)'], PAGE)).toEqual(['epubcfi(/6/4!/4/4/1:30)'])
    expect(visibleBookmarks(['epubcfi(/6/4!/4/4/1:30)'], '')).toEqual([])
    expect(visibleBookmarks([], PAGE)).toEqual([])
  })
})
