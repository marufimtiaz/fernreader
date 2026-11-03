import { describe, expect, it } from 'vitest'
import { POLYFILL_ATTR, polyfillMfenced } from '../src/reader/mathml'
import { resolveTextRange, textNodes, textOffsetOf } from '../src/reader/textpos'

const M = 'http://www.w3.org/1998/Math/MathML'
const xhtml = (math: string) => new DOMParser().parseFromString(
  `<html xmlns="http://www.w3.org/1999/xhtml"><body><math xmlns="${M}">${math}</math></body></html>`, 'application/xhtml+xml')
const shape = (el: Element): string => el.localName === 'mo' ? `mo(${el.textContent})`
  : el.children.length ? `${el.localName}[${[...el.children].map(shape).join(' ')}]` : `${el.localName}(${el.textContent})`

// F-083 (linear-algebra): MathML Core engines (Chromium) do not render <mfenced>, so matrix brackets were missing.
describe('polyfillMfenced: <mfenced> becomes an <mrow> with <mo> fences (MathML 3 meaning)', () => {
  it('a bracketed matrix keeps its brackets around the table', () => {
    const doc = xhtml('<mfenced separators="" open="[" close="]"><mtable><mtr><mtd><mn>1</mn></mtd></mtr></mtable></mfenced>')
    expect(polyfillMfenced(doc)).toBe(1)
    const math = doc.getElementsByTagNameNS(M, 'math')[0]
    expect(shape(math)).toBe('math[mrow[mo([) mtable[mtr[mtd[mn(1)]]] mo(])]]')
    expect(doc.getElementsByTagNameNS(M, 'mfenced')).toHaveLength(0)
    expect(math.firstElementChild!.namespaceURI).toBe(M)
  })
  it('defaults: parentheses and comma separators between the children; the last separator repeats', () => {
    const doc = xhtml('<mfenced><mi>a</mi><mi>b</mi><mi>c</mi></mfenced><mfenced separators=";|"><mi>x</mi><mi>y</mi><mi>z</mi><mi>w</mi></mfenced>')
    polyfillMfenced(doc)
    const [a, b] = [...doc.getElementsByTagNameNS(M, 'math')[0].children]
    expect(shape(a)).toBe('mrow[mo(() mi(a) mo(,) mi(b) mo(,) mi(c) mo())]')
    expect(shape(b)).toBe('mrow[mo(() mi(x) mo(;) mi(y) mo(|) mi(z) mo(|) mi(w) mo())]')
  })
  it('an empty open or close fence is left out; nested mfenced are rewritten too; id and class kept', () => {
    const doc = xhtml('<mfenced id="f" class="k" separators="" open="|" close=""><mfenced open="{" close="}"><mi>v</mi></mfenced></mfenced>')
    expect(polyfillMfenced(doc)).toBe(2)
    const outer = doc.getElementsByTagNameNS(M, 'math')[0].firstElementChild!
    expect(shape(outer)).toBe('mrow[mo(|) mrow[mo({) mi(v) mo(})]]')
    expect(outer.id).toBe('f')
    expect(outer.getAttribute('class')).toBe('k')
  })
  it('a document without MathML is untouched', () => {
    const doc = new DOMParser().parseFromString('<html><body><p>x</p></body></html>', 'text/html')
    expect(polyfillMfenced(doc)).toBe(0)
  })
})

// Review fix 7 (C-083): the fences the polyfill adds are not book text, so book-page and search offsets (indexed on the
// canonical text, which has no fence characters) do not drift by one or more characters after each <mfenced>.
describe('polyfillMfenced and text offsets', () => {
  it('text after rewritten mfenced keeps its canonical offset; search still resolves it', () => {
    const doc = new DOMParser().parseFromString(
      `<html xmlns="http://www.w3.org/1999/xhtml"><body><p>Before <math xmlns="${M}"><mfenced><mi>a</mi><mi>b</mi></mfenced></math>`
      + ` middle <math xmlns="${M}"><mfenced open="[" close="]" separators=";"><mi>c</mi><mi>d</mi></mfenced></math> after the end.</p></body></html>`,
      'application/xhtml+xml')
    const canonical = textNodes(doc).map(n => n.data).join('') // before the polyfill: what the app's extractor sees
    expect(canonical).toBe('Before ab middle cd after the end.')
    expect(polyfillMfenced(doc)).toBe(2)
    expect(doc.body.textContent).toContain('(a,b)') // the fences are drawn
    const after = textNodes(doc).find(n => n.data.includes('after the end'))!
    expect(textOffsetOf(doc, after, 1)).toBe(canonical.indexOf('after the end'))
    expect(resolveTextRange(doc, canonical.indexOf('after'), 5, 'after')?.toString()).toBe('after')
    expect(textNodes(doc).map(n => n.data).join('')).toBe(canonical)
    for (const mo of doc.getElementsByTagNameNS(M, 'mo')) expect(mo.hasAttribute(POLYFILL_ATTR)).toBe(true)
  })
})
