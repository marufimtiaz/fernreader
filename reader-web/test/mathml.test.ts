import { describe, expect, it } from 'vitest'
import { polyfillMfenced } from '../src/reader/mathml'

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
