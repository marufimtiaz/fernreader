import { describe, expect, it, vi } from 'vitest'
import { applySettings, DEFAULT_SETTINGS, fixedSides, markWrappers, readerCss, THEMES, type WebSettings } from '../src/reader/styles'

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

// C-082: the side margin is the whole edge-to-text distance; the book's horizontal margins/padding on html, body and
// wrappers around the whole content don't add to it, indents inside the text stay.
describe('markWrappers (C-082)', () => {
  const parse = (body: string) => new DOMParser().parseFromString(
    `<html xmlns="http://www.w3.org/1999/xhtml"><head></head><body>${body}</body></html>`, 'application/xhtml+xml')
  // wrappers only (html and body are marked too)
  const marked = (doc: Document) => [...doc.querySelectorAll('body [data-er-wrapper]')].map(e => e.getAttribute('id'))
  it('marks a chain of single wrappers around the whole content', () => {
    const doc = parse('<div id="a"><section id="b"><p>one</p><p>two</p></section></div>')
    markWrappers(doc)
    expect(marked(doc)).toEqual(['a', 'b'])
  })
  it('stops at a level with several blocks, text beside the element, or a non-wrapper element', () => {
    const several = parse('<div id="a"><p>x</p></div><p>y</p>')
    markWrappers(several); expect(marked(several)).toEqual([])
    const text = parse('<div id="a"><div id="b"><p>x</p></div>loose text</div>')
    markWrappers(text); expect(marked(text)).toEqual(['a'])
    const quote = parse('<blockquote id="q"><p>x</p></blockquote>')
    markWrappers(quote); expect(marked(quote)).toEqual([])
  })
  it('from a counting box: each box stands for a section body (the page counter batches sections, C-003)', () => {
    const doc = parse('<div class="er-box"><div id="a"><p>x</p><p>y</p></div></div><div class="er-box"><section id="b"><p>z</p></section></div>')
    markWrappers(doc)
    expect(marked(doc)).toEqual([]) // body holds several boxes: no wrapper from body
    for (const box of doc.querySelectorAll('.er-box')) markWrappers(doc, box)
    expect(marked(doc)).toEqual(['a', 'b'])
  })
  it('a single counting box is never marked from body (its padding is the side margin), its wrapper is', () => {
    const doc = parse('<div class="er-box"><div id="a"><p>x</p><p>y</p></div></div>')
    applySettings(doc, DEFAULT_SETTINGS)
    for (const box of doc.querySelectorAll('.er-box')) markWrappers(doc, box)
    expect(doc.querySelector('.er-box')!.hasAttribute('data-er-wrapper')).toBe(false)
    expect(marked(doc)).toEqual(['a'])
  })
  it('is idempotent and applySettings runs it', () => {
    const doc = parse('<div id="a"><p>x</p></div>')
    applySettings(doc, DEFAULT_SETTINGS); applySettings(doc, DEFAULT_SETTINGS)
    expect(marked(doc)).toEqual(['a'])
  })
  it('the reader CSS zeroes only the marked sides of marked elements (html, body, wrappers)', () => {
    const rules = readerCss('/').split('\n').filter(l => l.includes('data-er-wrapper'))
    expect(rules).toHaveLength(4)
    for (const [t, p] of [['ml', 'margin-left'], ['mr', 'margin-right'], ['pl', 'padding-left'], ['pr', 'padding-right']]) {
      const rule = rules.find(r => r.includes(`~="${t}"`))!
      expect(rule).toContain(`html[data-er-theme][data-er-wrapper~="${t}"], html[data-er-theme] [data-er-wrapper~="${t}"] { ${p}: 0 !important; }`)
    }
    for (const r of rules) expect(r).not.toMatch(/margin-(top|bottom)|padding-(top|bottom)|text-indent|text-align|justify/)
    expect(readerCss('/')).not.toMatch(/html\[data-er-theme\] body \{[^}]*margin-left/) // body is no longer zeroed blindly
  })
})

// C-088 (amends C-082): only fixed horizontal margins and padding are ignored; centring stays.
describe('fixed sides (C-088)', () => {
  // jsdom computes styles (cascaded values) only for the window's own document: each case rewrites it.
  const parse = (css: string, body: string) => {
    for (const a of [...document.documentElement.attributes]) document.documentElement.removeAttribute(a.name)
    document.body.removeAttribute('data-er-wrapper')
    document.head.innerHTML = `<style>${css}</style>`
    document.body.innerHTML = body
    return document
  }
  const sides = (doc: Document, sel: string) => doc.querySelector(sel)!.getAttribute('data-er-wrapper')
  it('auto margins are kept, fixed lengths (px, em, %) and padding are zeroed', () => {
    const v = (m: Record<string, string>) => (p: string) => m[p] ?? '0px'
    expect(fixedSides(v({}))).toEqual(['ml', 'mr', 'pl', 'pr'])
    expect(fixedSides(v({ 'margin-left': 'auto', 'margin-right': 'auto', 'padding-left': '2em' }))).toEqual(['pl', 'pr'])
    expect(fixedSides(v({ 'margin-left': 'auto', 'margin-right': '10%' }))).toEqual(['mr', 'pl', 'pr'])
    expect(fixedSides(v({ 'margin-left': '5%', 'margin-right': '3em' }))).toEqual(['ml', 'mr', 'pl', 'pr'])
    // C-088 as amended: a wrapper with its own narrower width keeps its side margins (`width: 60%; margin: 0 20%`)
    expect(fixedSides(v({ 'margin-left': '20%', 'margin-right': '20%' }), true)).toEqual(['pl', 'pr'])
    expect(fixedSides(v({ 'margin-left': 'auto', 'margin-right': 'auto' }), true)).toEqual(['pl', 'pr'])
  })
  it('a narrow centred title-page div (60 %, margin auto) keeps its auto margins; its padding goes', () => {
    const doc = parse('#t { width: 60%; margin: 0 auto; padding: 0 1em; text-align: center }', '<div id="t"><h1>Title</h1><p>Author</p></div>')
    applySettings(doc, DEFAULT_SETTINGS)
    expect(sides(doc, '#t')).toBe('pl pr')
  })
  it('a wrapper with fixed margins and padding has all four sides zeroed; body and html are marked the same way', () => {
    const doc = parse('body { margin: 0 3em; padding: 0 1em } #w { margin: 0 5%; padding: 0 2em }', '<div id="w"><p>a</p><p>b</p></div>')
    applySettings(doc, DEFAULT_SETTINGS)
    expect(sides(doc, '#w')).toBe('ml mr pl pr')
    expect(sides(doc, 'body')).toBe('ml mr pl pr')
    expect(doc.documentElement.getAttribute('data-er-wrapper')).toBe('ml mr pl pr')
  })
  it('a body centred with auto margins stays centred', () => {
    const doc = parse('body { max-width: 30em; margin: 0 auto; padding: 0 1em }', '<p>a</p><p>b</p>')
    applySettings(doc, DEFAULT_SETTINGS)
    expect(sides(doc, 'body')).toBe('pl pr')
  })
  it('the page counter marks a book wrapper in its box exactly as the reader does in the section (C-003)', () => {
    const css = '.title { width: 60%; margin-left: auto; margin-right: auto; padding: 0 4px }'
    const shown = parse(css, '<div class="title"><p>T</p></div>')
    applySettings(shown, DEFAULT_SETTINGS)
    const onScreen = sides(shown, '.title')
    const counted = parse(css, '<div class="er-box"><div class="title"><p>T</p></div></div><div class="er-box"><p>x</p></div>')
    applySettings(counted, DEFAULT_SETTINGS)
    for (const box of counted.querySelectorAll('.er-box')) markWrappers(counted, box)
    expect(sides(counted, '.title')).toBe(onScreen)
    expect(onScreen).toBe('pl pr')
  })
  it('marking again (every settings change) gives the same marks and does not rewrite the attribute', () => {
    const doc = parse('#t { width: 60%; margin: 0 auto }', '<div id="t"><p>x</p></div>')
    applySettings(doc, DEFAULT_SETTINGS)
    const el = doc.querySelector('#t')!
    let writes = 0
    new MutationObserver(() => { writes++ }).observe(el, { attributes: true })
    applySettings(doc, DEFAULT_SETTINGS)
    expect(el.getAttribute('data-er-wrapper')).toBe('pl pr')
    return new Promise<void>(r => setTimeout(() => { expect(writes).toBe(0); r() }, 0))
  })
})

describe('C-088: the wrapper width check stays off html and body', () => {
  it('applySettings reads no layout from body, whose max-width the paginator always sets', () => {
    const doc = document.implementation.createHTMLDocument('t')
    doc.body.style.maxWidth = '400px'
    doc.body.innerHTML = '<p>One</p><p>Two</p>'
    const rect = vi.spyOn(doc.body, 'getBoundingClientRect')
    applySettings(doc, DEFAULT_SETTINGS)
    expect(rect).not.toHaveBeenCalled()
  })
})
