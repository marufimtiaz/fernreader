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
}

export const DEFAULT_SETTINGS: WebSettings = {
  fontScale: 1, fontFamily: 'publisher', lineHeight: 1.5, marginPercent: 5, align: 'publisher', theme: 'light', flow: 'paginated',
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
  ]
  const themes = Object.entries(THEMES).map(([id, t]) =>
    `html[data-er-theme="${id}"] { --er-bg: ${t.bg}; --er-fg: ${t.fg}; --er-link: ${t.link}; --er-rule: ${t.rule}; color-scheme: ${t.dark ? 'dark' : 'light'}; }`)
  const recolor = Object.entries(THEMES).filter(([id]) => id !== 'light').map(([id]) => `html[data-er-theme="${id}"]`)
  const any = (sel: string) => recolor.map(h => `${h} ${sel}`).join(', ')
  return [
    ...faces,
    'html { font-size: calc(100% * var(--er-font-scale, 1)) !important; }',
    'html[data-er-lh] body, html[data-er-lh] body :is(p, li, blockquote, dd, dt) { line-height: var(--er-line-height) !important; }',
    'html[data-er-align="start"] body :is(p, li, blockquote, dd) { text-align: start !important; }',
    'html[data-er-align="justify"] body :is(p, li, blockquote, dd) { text-align: justify !important; }',
    'html[data-er-font] body, html[data-er-font] body :not(code, pre, kbd, samp, tt, code *, pre *) { font-family: var(--er-font) !important; }',
    ...themes,
    'html[data-er-theme] { background-color: var(--er-bg) !important; color: var(--er-fg) !important; }',
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
}
