/**
 * Link handling helpers (F-042..F-044): which links are note references, which external targets may leave the app,
 * and the script-free copy of a note's content that the footnote popup shows (R-007). The heuristics follow the ideas
 * of foliate-js footnotes.js (epub:type / ARIA roles, superscript links), written here so the popup can live in the
 * host page instead of a second foliate view.
 */

const OPS = 'http://www.idpf.org/2007/ops'
const XHTML = 'http://www.w3.org/1999/xhtml'

const words = (s: string | null | undefined) => new Set((s ?? '').split(/\s+/).filter(Boolean))
const types = (el: Element) => words(el.getAttributeNS?.(OPS, 'type') ?? el.getAttribute('epub:type'))
const roles = (el: Element) => words(el.getAttribute('role'))

/** Schemes that may be handed to another app; everything else (javascript:, file:, content:, intent:, data:…) is ignored. */
const EXTERNAL = new Set(['http:', 'https:', 'mailto:'])

/** The normalised URL of an external link the app may open (http, https with a host; mailto), or null. */
export function externalHref(href: string): string | null {
  let u: URL
  try { u = new URL(href.trim()) } catch { return null }
  if (!EXTERNAL.has(u.protocol)) return null
  if (u.protocol !== 'mailto:' && !u.hostname) return null
  if (u.protocol === 'mailto:' && !u.pathname) return null
  return u.href
}

const isSuper = (el: Element | null | undefined): boolean => {
  if (!el) return false
  if (el.localName === 'sup') return true
  const style = el.ownerDocument?.defaultView?.getComputedStyle?.(el)
  const va = style?.verticalAlign ?? ''
  return va === 'super' || va === 'top' || va === 'text-top' || /^\d/.test(va)
}

const REF_TYPES = ['noteref', 'biblioref', 'glossref']
const REF_ROLES = ['doc-noteref', 'doc-biblioref', 'doc-glossref']

/**
 * 'yes': marked as a note reference; 'maybe': looks like one (a superscript link that is not a backlink), shown as a
 * popup when its target can be extracted; 'no': an ordinary link.
 */
export function noteRef(a: Element): 'yes' | 'maybe' | 'no' {
  const t = types(a), r = roles(a)
  if (REF_TYPES.some(x => t.has(x)) || REF_ROLES.some(x => r.has(x))) return 'yes'
  if (t.has('backlink') || r.has('doc-backlink')) return 'no'
  const sup = isSuper(a) || (a.children.length === 1 && isSuper(a.children[0])) || isSuper(a.parentElement)
  return sup ? 'maybe' : 'no'
}

const NOTE_TYPES = ['footnote', 'endnote', 'rearnote', 'note']
const NOTE_ROLES = ['doc-footnote', 'doc-endnote', 'note']

/** The link's target is itself marked as a note (so even an unmarked link to it opens the popup). */
export function isNoteTarget(el: Element | null | undefined): boolean {
  if (!el) return false
  const t = types(el), r = roles(el)
  return NOTE_TYPES.some(x => t.has(x)) || NOTE_ROLES.some(x => r.has(x))
}

const INLINE = new Set(['a', 'span', 'sup', 'sub', 'em', 'strong', 'i', 'b', 'small', 'big'])

/**
 * The element to show for a link target (an element or a Range from foliate's fragment lookup): an inline target
 * (e.g. an anchor at the start of a note paragraph) stands for the block around it.
 */
export function noteElement(target: Element | Range | null | undefined): Element | null {
  if (!target) return null
  let el: Element | null = 'startContainer' in target
    ? (target.startContainer.nodeType === 1 ? target.startContainer as Element : target.startContainer.parentElement)
    : target
  while (el && INLINE.has(el.localName) && el.parentElement && el.parentElement.localName !== 'body') el = el.parentElement
  return el
}

/** Copied with their children (and no attributes beyond the few below). */
const KEEP = new Set([
  'p', 'div', 'span', 'a', 'em', 'strong', 'i', 'b', 'u', 's', 'sub', 'sup', 'small', 'big', 'br', 'ol', 'ul', 'li',
  'blockquote', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'code', 'pre', 'cite', 'q', 'abbr', 'dfn', 'dl', 'dt', 'dd', 'table',
  'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'caption', 'aside', 'section', 'hr', 'ruby', 'rt', 'rp', 'mark', 'var',
  'kbd', 'samp', 'del', 'ins', 'bdi', 'bdo', 'figure', 'figcaption', 'header', 'footer',
])
/** Dropped with everything inside (scripts, styles, embedded content, forms). Unknown elements are unwrapped. */
const DROP = new Set([
  'script', 'style', 'link', 'meta', 'title', 'head', 'iframe', 'frame', 'object', 'embed', 'video', 'audio', 'source',
  'track', 'canvas', 'template', 'noscript', 'button', 'input', 'form', 'select', 'textarea', 'map', 'area', 'picture',
])
const ATTRS = new Set(['dir', 'lang', 'colspan', 'rowspan'])

/**
 * How a link inside a footnote popup is resolved: an absolute URL (any scheme) is kept exactly as written (foliate's
 * section `resolveHref` would drop its query string), anything else is resolved against the note's [section]. A
 * resolver that fails, or no section, keeps the href as written.
 */
export function noteLinkResolver(section: { resolveHref?(href: string): string | null | undefined } | undefined): (href: string) => string {
  return href => {
    if (/^[a-z][\w+.-]*:/i.test(href)) return href
    try { return section?.resolveHref?.(href) ?? href } catch { return href }
  }
}

/**
 * A copy of a note's content for the host page: element and text nodes only, an allow-list of plain markup, no
 * attributes but `dir`/`lang`/spans; images become their alt text; SVG and MathML are left out. Links lose their href
 * (a click in the host page must never navigate it) and keep their target, resolved against the note's section by
 * [resolve], in `data-er-href`. An `aside` or `li` note gives its content, any other note element itself.
 */
export function sanitizeNote(note: Element, into: Document, resolve: (href: string) => string | null): DocumentFragment {
  const out = into.createDocumentFragment()
  const copy = (n: Node, parent: Node) => {
    if (n.nodeType === 3) { parent.appendChild(into.createTextNode(n.nodeValue ?? '')); return }
    if (n.nodeType !== 1) return
    const el = n as Element
    if (el.namespaceURI !== XHTML && el.namespaceURI !== null) return // SVG, MathML
    const name = el.localName.toLowerCase()
    if (DROP.has(name)) return
    if (name === 'img' || name === 'image') {
      const alt = el.getAttribute('alt')?.trim()
      if (alt) parent.appendChild(into.createTextNode(alt))
      return
    }
    if (!KEEP.has(name)) { el.childNodes.forEach(c => copy(c, parent)); return }
    const e = into.createElement(name)
    for (const a of ATTRS) { const v = el.getAttribute(a); if (v != null) e.setAttribute(a, v) }
    if (name === 'a') {
      const href = el.getAttribute('href') ?? el.getAttributeNS('http://www.w3.org/1999/xlink', 'href')
      if (href) {
        e.setAttribute('data-er-href', resolve(href) ?? href)
        e.setAttribute('role', 'link')
        e.setAttribute('tabindex', '0')
      }
    }
    el.childNodes.forEach(c => copy(c, e))
    parent.appendChild(e)
  }
  if (note.localName === 'aside' || note.localName === 'li') note.childNodes.forEach(c => copy(c, out))
  else copy(note, out)
  return out
}
