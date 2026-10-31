import { describe, expect, it } from 'vitest'
import { contentSteps, clampToRange } from '../src/reader/position'

const doc = () => new DOMParser().parseFromString(
  '<html><head><title>t</title></head><body><p>alpha one</p><p>beta two</p>\n<p>gamma three</p>\n</body></html>', 'text/html')
const steps = (cfi: string) => contentSteps(cfi)!

describe('clampToRange (C-009)', () => {
  it('resolves a valid path exactly', () => {
    const r = clampToRange(doc(), steps('epubcfi(/6/4!/4/2/1:3)'))
    expect(r.startContainer.textContent).toBe('alpha one')
    expect(r.startOffset).toBe(3)
  })
  it('past the end of the chapter lands on its last character', () => {
    const r = clampToRange(doc(), steps('epubcfi(/6/4!/4/40)'))
    expect(r.startContainer.textContent).toBe('gamma three')
    expect(r.toString()).toBe('e')
  })
  it('offset past the end of a text node lands at the end of that text', () => {
    const r = clampToRange(doc(), steps('epubcfi(/6/4!/4/4/1:9999)'))
    expect(r.startContainer.textContent).toBe('beta two')
    expect(r.toString()).toBe('o')
  })
  it('a path past the whole document lands on its last text', () => {
    const r = clampToRange(doc(), steps('epubcfi(/6/4!/99)'))
    expect(r.toString()).toBe('e')
  })
  it('a range CFI uses its start', () => {
    const r = clampToRange(doc(), steps('epubcfi(/6/4!/4,/2/1:1,/2/1:4)'))
    expect(r.startContainer.textContent).toBe('alpha one')
    expect(r.startOffset).toBe(1)
  })
})
