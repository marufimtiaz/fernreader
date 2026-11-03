/**
 * Reader styles (F-070..F-074). One style sheet is injected into every section document once; every setting is a
 * CSS custom property or a `data-er-*` attribute on the section's `<html>`, so a change rewrites no style text and
 * only reflows (P-020). The page counter applies the same to its batch documents, so counted pages match (C-003).
 */
export type WebSettings = {
  fontScale: number
  fontFamily: string
  lineHeight: number
  marginPercent: number
  align: 'publisher' | 'start' | 'justify'
  theme: 'light' | 'dark' | 'black' | 'warm'
  flow: 'paginated' | 'scrolled'
  /** F-032: which horizontal swipe is "next"; 'auto' = the book's page-progression-direction. Optional (older apps). */
  swipeDirection?: 'auto' | 'ltr' | 'rtl'
  /** F-032: 'slide' = foliate's animated turn, 'none' = instant. Optional (older apps). */
  pageAnimation?: 'slide' | 'none'
  /**
   * C-085, scrolled flow only: 'continuous' = the next chapter follows below (C-079); 'one-at-a-time' = scrolling stops
   * at the chapter's end, with "Next"/"Previous" controls and an overscroll pull. Optional (older apps: continuous).
   */
  chapters?: 'continuous' | 'one-at-a-time'
}

export const DEFAULT_SETTINGS: WebSettings = {
  fontScale: 1, fontFamily: 'publisher', lineHeight: 1.5, marginPercent: 5, align: 'publisher', theme: 'light', flow: 'paginated',
  swipeDirection: 'auto', pageAnimation: 'slide', chapters: 'continuous',
}

/** Reading themes (our own palette; mirrored in ui/.../reader/display/ReadingTheme.kt). */
export const THEMES: Record<WebSettings['theme'], { bg: string; fg: string; link: string; rule: string; dark: boolean }> = {
  light: { bg: '#FBFAF7', fg: '#1F1D1A', link: '#2F5D50', rule: '#D8D4CC', dark: false },
  dark: { bg: '#212427', fg: '#DAD6CE', link: '#8CC7B4', rule: '#3A3E42', dark: true },
  black: { bg: '#000000', fg: '#C4C0B8', link: '#7FB8A6', rule: '#2A2A2A', dark: true },
  warm: { bg: '#F4E9D3', fg: '#3A2E21', link: '#7A4E1E', rule: '#D9C7A6', dark: false },
}

/** Bundled SIL OFL 1.1 faces (served from assets/reader/fonts). */
export const FONTS: Record<string, string> = {
  literata: '"Literata", Georgia, serif',
  'source-sans': '"Source Sans 3", system-ui, sans-serif',
  atkinson: '"Atkinson Hyperlegible", system-ui, sans-serif',
}

const face = (family: string, file: string, style: string, weight: string, base: string) =>
  `@font-face { font-family: "${family}"; src: url("${base}fonts/${file}") format("truetype"); font-style: ${style}; font-weight: ${weight}; font-display: block; }`

/** [base] = the host page's directory URL, e.g. https://appassets.androidplatform.net/assets/reader/ */
export function readerCss(base: string): string {
  const faces = [
    face('Literata', 'literata.ttf', 'normal', '200 900', base),
    face('Literata', 'literata-italic.ttf', 'italic', '200 900', base),
    face('Source Sans 3', 'source-sans-3.ttf', 'normal', '200 900', base),
    face('Source Sans 3', 'source-sans-3-italic.ttf', 'italic', '200 900', base),
    face('Atkinson Hyperlegible', 'atkinson-regular.ttf', 'normal', '400', base),
    face('Atkinson Hyperlegible', 'atkinson-italic.ttf', 'italic', '400', base),
    face('Atkinson Hyperlegible', 'atkinson-bold.ttf', 'normal', '700', base),
    face('Atkinson Hyperlegible', 'atkinson-bolditalic.ttf', 'italic', '700', base),
    // C-047: an OpenType MATH table, so fences and radicals stretch (Android ships no math font).
    face('Noto Sans Math', 'noto-sans-math.ttf', 'normal', '400', base),
  ]
  const themes = Object.entries(THEMES).map(([id, t]) =>
    `html[data-er-theme="${id}"] { --er-bg: ${t.bg}; --er-fg: ${t.fg}; --er-link: ${t.link}; --er-rule: ${t.rule}; }`)
  const recolor = Object.entries(THEMES).filter(([id]) => id !== 'light').map(([id]) => `html[data-er-theme="${id}"]`)
  const any = (sel: string) => recolor.map(h => `${h} ${sel}`).join(', ')
  return [
    ...faces,
    'html { font-size: calc(100% * var(--er-font-scale, 1)) !important; }',
    'html[data-er-lh] body, html[data-er-lh] body :is(p, li, blockquote, dd, dt) { line-height: var(--er-line-height) !important; }',
    'html[data-er-align="start"] body :is(p, li, blockquote, dd) { text-align: start !important; }',
    'html[data-er-align="justify"] body :is(p, li, blockquote, dd) { text-align: justify !important; }',
    'html[data-er-font] body, html[data-er-font] body :not(code, pre, kbd, samp, tt, code *, pre *, math, math *) { font-family: var(--er-font) !important; }',
    'math { font-family: "Noto Sans Math", math !important; }',
    // C-082: the side margin setting is the whole edge-to-text distance: the book's own fixed horizontal margins and
    // padding on html, body and wrappers around the whole content don't add to it. applySettings marks each such
    // element with the sides to zero (data-er-wrapper tokens, see markWrappers); auto margins are never zeroed, so a
    // block the book centres stays centred (C-088). Indents of blocks inside the text (blockquotes, lists, centred
    // headings) stay. The paginator's inline !important padding on html wins.
    ...SIDES.map(([token, prop]) =>
      `html[data-er-theme][data-er-wrapper~="${token}"], html[data-er-theme] [data-er-wrapper~="${token}"] { ${prop}: 0 !important; }`),
    ...themes,
    // The paper is the host page (it follows the theme); section documents stay transparent, so margins, the page and
    // the edges always have the same colour, also right after a theme change.
    'html[data-er-theme], html[data-er-theme] body { background-color: transparent !important; }',
    'html[data-er-theme] { color: var(--er-fg) !important; }',
    // Dark-ish themes take the book's colours over: text in the theme colour, no page-coloured boxes (images stay).
    `${any('body')}, ${any('body *:not(img, svg, svg *, video, image)')} { color: inherit !important; background-color: transparent !important; border-color: var(--er-rule) !important; }`,
    `${any('a:any-link')} { color: var(--er-link) !important; }`,
  ].join('\n')
}

/** Applies [s] to one section document: variables and attributes on `<html>` only. */
export function applySettings(doc: Document, s: WebSettings) {
  const html = doc.documentElement
  if (!html) return
  html.style.setProperty('--er-font-scale', String(s.fontScale))
  html.style.setProperty('--er-line-height', String(s.lineHeight))
  html.setAttribute('data-er-lh', '')
  if (s.align === 'publisher') html.removeAttribute('data-er-align')
  else html.setAttribute('data-er-align', s.align)
  const font = FONTS[s.fontFamily]
  if (font) { html.setAttribute('data-er-font', s.fontFamily); html.style.setProperty('--er-font', font) }
  else { html.removeAttribute('data-er-font'); html.style.removeProperty('--er-font') }
  html.setAttribute('data-er-theme', THEMES[s.theme] ? s.theme : 'light')
  markSides(html)
  if (doc.body) markSides(doc.body)
  markWrappers(doc)
}

const WRAPPER = new Set(['div', 'section', 'article', 'main'])

/** C-082 sides: the data-er-wrapper token of each horizontal margin and padding, and its property. */
const SIDES = [['ml', 'margin-left'], ['mr', 'margin-right'], ['pl', 'padding-left'], ['pr', 'padding-right']] as const

/**
 * C-088: the computed value of [prop] on [el] as written: 'auto' for an auto margin. Typed OM gives computed values
 * (getComputedStyle resolves a rendered element's margins to the used px, so auto would look fixed); without it
 * (jsdom in tests) getComputedStyle's value, which is the cascaded one there.
 */
function sideValue(el: Element, prop: string): string {
  const map = (el as Element & { computedStyleMap?: () => { get(p: string): unknown } }).computedStyleMap
  if (typeof map === 'function') {
    try { return String(map.call(el).get(prop) ?? '') } catch { /* fall through */ }
  }
  const view = el.ownerDocument.defaultView ?? (typeof window === 'undefined' ? null : window) // a parsed document has no view
  return view?.getComputedStyle(el).getPropertyValue(prop) ?? ''
}

/**
 * C-082/C-088: the sides of [el] (a whole-content element: html, body or a wrapper) whose horizontal margin or padding
 * is a fixed length, as data-er-wrapper tokens: the reader CSS zeroes those. An auto margin is centring (with a width,
 * or in a flex container) and is kept; padding is never auto. Pure function of the values, for tests.
 */
export function fixedSides(value: (prop: string) => string): string[] {
  return SIDES.filter(([, prop]) => value(prop).trim() !== 'auto').map(([token]) => token)
}

/**
 * Marks [el] with the sides to zero (data-er-wrapper="ml mr pl pr", fewer where a margin is auto). Decided from its
 * computed style each time (the tokens zero only fixed sides, so a side that was zeroed reads as a fixed 0 again: the
 * decision is stable); the attribute is only written when it changes. A few elements per document: cheap.
 */
function markSides(el: Element) {
  const tokens = fixedSides(p => sideValue(el, p)).join(' ')
  if (el.getAttribute('data-er-wrapper') !== tokens) el.setAttribute('data-er-wrapper', tokens)
}

/**
 * C-082: marks the chain of elements that wrap the whole body content (`<body><div class="main"><section>…`): each
 * is the only element child of its parent, with no text beside it. Their fixed horizontal margins and padding are then
 * neutralised by [readerCss]; auto margins stay (C-088, markSides). Idempotent; a document with several top-level
 * blocks has no wrapper. [start] is the element standing for the section's body: the page counter puts each
 * section's body in its own box (counter.ts), so a book's wrappers get the same marks there as on screen (C-003).
 */
export function markWrappers(doc: Document, start: Element | null = doc.body) {
  let el: Element | null = start
  while (el) {
    const kids: Element[] = Array.from(el.children)
    const text = Array.from(el.childNodes).some(n => n.nodeType === 3 && (n.nodeValue ?? '').trim() !== '')
    const only: Element | null = kids.length === 1 && !text ? kids[0] : null
    // the page counter's section boxes are its column boxes, never a book's wrapper (their padding is the margin)
    if (!only || !WRAPPER.has(only.localName) || only.classList.contains('er-box')) return
    markSides(only)
    el = only
  }
}
