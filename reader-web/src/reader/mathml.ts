const MATHML = 'http://www.w3.org/1998/Math/MathML'
const KEEP = ['id', 'class', 'style', 'dir', 'mathcolor', 'mathbackground', 'displaystyle', 'scriptlevel']

/**
 * F-083: MathML Core engines (Chromium, the WebView) do not render `<mfenced>` (deprecated in MathML 3, absent from
 * MathML Core), so a book's matrix brackets and parentheses disappear. Each one is rewritten as the `<mrow>` it stands
 * for: the open fence, the children with separators between them (the last separator repeats; default ","), the close
 * fence (defaults "(" and ")"; an empty fence is left out). Runs before layout, on the shown document and on the page
 * counter's documents alike (C-003). Returns how many were rewritten.
 */
export function polyfillMfenced(doc: Document): number {
  const list = [...doc.getElementsByTagNameNS(MATHML, 'mfenced')]
  for (const el of list) {
    const open = el.getAttribute('open') ?? '('
    const close = el.getAttribute('close') ?? ')'
    const seps = Array.from((el.getAttribute('separators') ?? ',').replace(/\s/g, ''))
    const mo = (text: string, fence: boolean) => {
      const o = doc.createElementNS(MATHML, 'mo')
      o.textContent = text
      o.setAttribute(fence ? 'fence' : 'separator', 'true')
      return o
    }
    const row = doc.createElementNS(MATHML, 'mrow')
    for (const a of KEEP) { const v = el.getAttribute(a); if (v != null) row.setAttribute(a, v) }
    if (open) row.append(mo(open, true))
    ;[...el.children].forEach((child, i) => {
      if (i > 0 && seps.length) row.append(mo(seps[Math.min(i - 1, seps.length - 1)], false))
      row.append(child)
    })
    if (close) row.append(mo(close, true))
    el.replaceWith(row)
  }
  return list.length
}
