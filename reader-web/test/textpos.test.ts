import { describe, expect, it } from 'vitest'
import { foldText, resolveTextRange } from '../src/reader/textpos'

const html = (body: string) => new DOMParser().parseFromString(`<html><body>${body}</body></html>`, 'text/html')
const textOf = (doc: Document) => doc.body.textContent ?? ''
const at = (r: Range | null) => (r ? r.toString() : null)

describe('foldText', () => {
  it('lowercases and strips diacritics, one output char per input char', () => {
    expect(foldText('Borodinó ÉCOLE Ångström')).toBe('borodino ecole angstrom')
    expect(foldText('Ǆ ß İ').length).toBe(5)
  })
})

describe('resolveTextRange', () => {
  it('finds the exact offset', () => {
    const doc = html('<p>The quick brown fox</p><p>jumps over</p>')
    const text = textOf(doc)
    const off = text.indexOf('brown')
    const r = resolveTextRange(doc, off, 5, 'brown')!
    expect(at(r)).toBe('brown')
  })

  it('resolve_offset_drifted_by_whitespace: nearest folded match within 500 chars', () => {
    const doc = html('<p>one  two\n\n   three four</p><p>five</p>')
    const text = textOf(doc)
    const real = text.indexOf('four')
    // The index counted one whitespace run as a single space: its offset is smaller than the DOM's.
    expect(at(resolveTextRange(doc, real - 4, 4, 'four'))).toBe('four')
    expect(at(resolveTextRange(doc, real + 6, 4, 'four'))).toBe('four')
  })

  it('picks the nearest of several candidates', () => {
    const doc = html('<p>cat dog cat dog cat</p>')
    const text = textOf(doc)
    const second = text.indexOf('cat', 5)
    const r = resolveTextRange(doc, second + 2, 3, 'cat')!
    expect(r.startContainer.textContent!.indexOf('cat', 5)).toBe(second)
    expect(r.startOffset).toBe(second)
  })

  it('does not look further than 500 chars', () => {
    const doc = html(`<p>${'x'.repeat(600)}needle</p>`)
    expect(resolveTextRange(doc, 0, 6, 'needle')).toBeNull()
    expect(at(resolveTextRange(doc, 150, 6, 'needle'))).toBe('needle')
  })

  it('matches case- and diacritic-insensitively', () => {
    const doc = html('<p>Battle of Borodinó began</p>')
    const off = textOf(doc).indexOf('Borodinó')
    expect(at(resolveTextRange(doc, off, 8, 'borodino'))).toBe('Borodinó')
    expect(at(resolveTextRange(doc, off, 8, 'BORODINÓ'))).toBe('Borodinó')
  })

  it('spans several text nodes', () => {
    const doc = html('<p>an <em>aug</em>mented <b>matrix</b> here</p>')
    const text = textOf(doc)
    const off = text.indexOf('augmented matrix')
    const r = resolveTextRange(doc, off, 16, 'augmented matrix')!
    expect(r.toString()).toBe('augmented matrix')
    expect(r.startContainer).not.toBe(r.endContainer)
  })

  it('excludes script, style and template text from the offsets', () => {
    const doc = html('<p>alpha</p><script>var beta = 1</script><style>.beta{}</style><template>beta</template><p>beta gamma</p>')
    // Visible text is "alphabeta gamma": 'beta' is at 5.
    const r = resolveTextRange(doc, 5, 4, 'beta')!
    expect(r.startContainer.parentElement!.tagName).toBe('P')
    expect(r.startContainer.textContent).toBe('beta gamma')
    expect(resolveTextRange(doc, 0, 4, 'var ')).toBeNull()
  })

  // jsdom cannot put a Range boundary inside a CDATA node (nodeLength is 0 there; browsers can), so the test checks
  // that CDATA content counts toward the offsets of the text after it.
  it('counts CDATA content of an XHTML document in the offsets', () => {
    const xml = '<html xmlns="http://www.w3.org/1999/xhtml"><body><p>before <![CDATA[<raw> text]]> after</p></body></html>'
    const doc = new DOMParser().parseFromString(xml, 'application/xhtml+xml')
    const off = 'before <raw> text '.length
    const r = resolveTextRange(doc, off, 5, 'after')!
    expect(r.toString()).toBe('after')
    expect(r.startContainer.nodeType).toBe(Node.TEXT_NODE)
  })

  it('returns null when the needle is not near the offset', () => {
    const doc = html('<p>nothing to see here</p>')
    expect(resolveTextRange(doc, 3, 4, 'zebra')).toBeNull()
    expect(resolveTextRange(doc, 3, 4, '')).toBeNull()
  })

  it('returns null for a document without a body', () => {
    expect(resolveTextRange(new DOMParser().parseFromString('<root/>', 'application/xml'), 0, 1, 'a')).toBeNull()
  })
})
