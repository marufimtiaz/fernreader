import { FONTS, THEMES, type WebSettings } from './styles'

/**
 * The footnote popup (F-043): an overlay in the host page over the book, with the note's sanitised content (see
 * links.ts sanitizeNote) in a themed, scrollable panel at most 60 % of the viewport high. Not a `<dialog>`: the
 * host page has no other focusable content, and a plain overlay behaves the same in every WebView version. A click
 * outside the panel dismisses it; a click on a link in it is handed to [onLink] with the link's resolved target.
 * The overlay covers the section frames, so no tap or swipe reaches the page while it is open.
 */
export class FootnotePopup {
  private root: HTMLElement | null = null
  private panel: HTMLElement | null = null

  constructor(private doc: Document, private onLink: (href: string) => void, private onDismiss: () => void) {}

  get isOpen() { return !!this.root }
  /** The panel text (instrumented tests), or null when closed. */
  text(): string | null { return this.panel?.textContent ?? null }

  show(content: DocumentFragment, s: WebSettings) {
    this.close()
    ensureStyle(this.doc)
    const t = THEMES[s.theme] ?? THEMES.light
    const root = this.doc.createElement('div')
    root.id = 'er-footnote'
    root.style.setProperty('--er-fn-bg', t.bg)
    root.style.setProperty('--er-fn-fg', t.fg)
    root.style.setProperty('--er-fn-link', t.link)
    root.style.setProperty('--er-fn-rule', t.rule)
    root.style.setProperty('--er-fn-scrim', t.dark ? 'rgba(0,0,0,0.6)' : 'rgba(0,0,0,0.32)')
    root.style.paddingTop = `${24 + (s.topInset ?? 0)}px` // C-099: centred in the room below the cutout
    const panel = this.doc.createElement('div')
    panel.setAttribute('role', 'dialog')
    panel.setAttribute('aria-modal', 'true')
    panel.style.fontSize = `${Math.round(16 * s.fontScale * 100) / 100}px`
    panel.style.lineHeight = String(s.lineHeight)
    const font = FONTS[s.fontFamily]
    if (font) panel.style.fontFamily = font
    panel.append(content)
    root.append(panel)
    root.addEventListener('click', e => {
      const target = e.target as Element | null
      if (target && panel.contains(target)) {
        const a = target.closest?.('[data-er-href]')
        if (a && panel.contains(a)) { e.preventDefault(); this.onLink(a.getAttribute('data-er-href')!) }
        return
      }
      this.close()
      this.onDismiss()
    })
    this.doc.body.append(root)
    this.root = root
    this.panel = panel
  }

  /** Returns whether it was open. */
  close(): boolean {
    if (!this.root) return false
    this.root.remove()
    this.root = null
    this.panel = null
    return true
  }
}

const STYLE_ID = 'er-footnote-style'
function ensureStyle(doc: Document) {
  if (doc.getElementById(STYLE_ID)) return
  const style = doc.createElement('style')
  style.id = STYLE_ID
  // Material-like sheet: 28 px corners, 24 px padding, scrim over the page; the panel scrolls for long notes.
  style.textContent = [
    '#er-footnote { position: fixed; inset: 0; z-index: 10; display: flex; align-items: center; justify-content: center; background: var(--er-fn-scrim); padding: 24px; box-sizing: border-box; }',
    '#er-footnote > [role="dialog"] { box-sizing: border-box; width: 100%; max-width: 560px; max-height: 60vh; overflow-y: auto; overscroll-behavior: contain; padding: 20px 24px; border-radius: 28px; background: var(--er-fn-bg); color: var(--er-fn-fg); box-shadow: 0 6px 24px rgba(0,0,0,0.3); font-family: inherit; overflow-wrap: break-word; -webkit-user-select: text; user-select: text; }',
    '#er-footnote [role="dialog"] > :first-child { margin-top: 0; }',
    '#er-footnote [role="dialog"] > :last-child { margin-bottom: 0; }',
    '#er-footnote [data-er-href] { color: var(--er-fn-link); text-decoration: underline; cursor: pointer; }',
    '#er-footnote hr { border: none; border-top: 1px solid var(--er-fn-rule); }',
  ].join('\n')
  doc.head.append(style)
}
