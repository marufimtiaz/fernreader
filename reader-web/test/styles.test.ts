import { describe, expect, it } from 'vitest'
import { applySettings, readerCss, THEMES, type WebSettings } from '../src/reader/styles'

const base: WebSettings = { fontScale: 1, fontFamily: 'publisher', lineHeight: 1.5, marginPercent: 5, align: 'publisher', theme: 'light', flow: 'paginated' }
const doc = () => {
  const d = new DOMParser().parseFromString('<html><head><style>p{color:red}</style></head><body><p>x</p></body></html>', 'text/html')
  const s = d.createElement('style'); s.textContent = readerCss('https://appassets.androidplatform.net/assets/reader/'); d.head.append(s)
  return d
}
const styleText = (d: Document) => [...d.querySelectorAll('style')].map(s => s.textContent).join('\n')

describe('readerCss', () => {
  const css = readerCss('https://appassets.androidplatform.net/assets/reader/')
  it('declares the bundled faces with absolute URLs (section documents are blob: URLs)', () => {
    for (const f of ['Literata', 'Source Sans 3', 'Atkinson Hyperlegible']) expect(css).toContain(`font-family: "${f}"`)
    expect(css).toContain('url("https://appassets.androidplatform.net/assets/reader/fonts/literata.ttf")')
    expect(css).not.toMatch(/url\("?\.\.?\//)
  })
  // C-047: MathML gets a font with an OpenType MATH table (stretchy fences, radicals), and the reader's font
  // override never reaches into a formula.
  it('gives math elements the bundled math font, outside the reader font override', () => {
    expect(css).toContain('@font-face { font-family: "Noto Sans Math"; src: url("https://appassets.androidplatform.net/assets/reader/fonts/noto-sans-math.ttf")')
    expect(css).toMatch(/(^|\n)math \{ font-family: "Noto Sans Math", math !important; \}/)
    const override = css.split('\n').find(r => r.includes('font-family: var(--er-font)'))!
    const selector = override.slice(0, override.indexOf('{')).trim()
    const d = new DOMParser().parseFromString('<html data-er-font="literata"><body><p>x</p><math xmlns="http://www.w3.org/1998/Math/MathML"><mrow><mo>[</mo><mi>A</mi></mrow></math></body></html>', 'text/html')
    expect(d.querySelector('p')!.matches(selector)).toBe(true)
    for (const el of d.querySelectorAll('math, math *')) expect(el.matches(selector)).toBe(false)
  })
  it('sets no color-scheme: a frame whose scheme differs from the host page gets an opaque #121212 canvas', () => {
    expect(css).not.toContain('color-scheme')
  })
  it('is written against variables and attributes only', () => {
    expect(css).toContain('var(--er-font-scale')
    expect(css).toContain('var(--er-line-height')
    for (const t of Object.keys(THEMES)) expect(css).toContain(`html[data-er-theme="${t}"]`)
  })
})

describe('applySettings', () => {
  it('changes html attributes and variables only, never the style sheets', () => {
    const d = doc(); const before = styleText(d)
    applySettings(d, { ...base, fontScale: 1.4, lineHeight: 1.8, align: 'justify', theme: 'dark', fontFamily: 'literata' })
    const html = d.documentElement
    expect(styleText(d)).toBe(before)
    expect(html.style.getPropertyValue('--er-font-scale')).toBe('1.4')
    expect(html.style.getPropertyValue('--er-line-height')).toBe('1.8')
    expect(html.getAttribute('data-er-align')).toBe('justify')
    expect(html.getAttribute('data-er-theme')).toBe('dark')
    expect(html.getAttribute('data-er-font')).toBe('literata')
    expect(html.style.getPropertyValue('--er-font')).toContain('Literata')
  })
  it('publisher choices remove the overrides', () => {
    const d = doc()
    applySettings(d, { ...base, align: 'justify', fontFamily: 'atkinson' })
    applySettings(d, base)
    expect(d.documentElement.hasAttribute('data-er-align')).toBe(false)
    expect(d.documentElement.hasAttribute('data-er-font')).toBe(false)
    expect(d.documentElement.getAttribute('data-er-theme')).toBe('light')
  })
  it('an unknown font id falls back to the publisher font', () => {
    const d = doc(); applySettings(d, { ...base, fontFamily: 'nope' })
    expect(d.documentElement.hasAttribute('data-er-font')).toBe(false)
  })
})
