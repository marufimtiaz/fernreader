/**
 * F-060 text selection: what the app's selection menu acts on. The browser's own selection (long-press, native
 * handles) is the source of truth; this module only reads it and reports it to the app once it has settled.
 */

/** [text]: exactly `Selection.toString()` of the section document (no trimming); [cfi]: the range's CFI (virtual). */
export type SelectionInfo = { text: string; cfi: string; sectionIndex: number }

/** The first loaded section document holding a non-collapsed, non-empty selection, or null. Never throws. */
export function readSelection(
  contents: ReadonlyArray<{ doc?: Document | null; index: number }>,
  cfiOf: (index: number, range: Range) => string,
): SelectionInfo | null {
  for (const c of contents) {
    try {
      const sel = c.doc?.getSelection?.()
      if (!sel || sel.isCollapsed || !sel.rangeCount) continue
      const text = sel.toString()
      if (!text) continue
      const cfi = cfiOf(c.index, sel.getRangeAt(0))
      if (!cfi) continue
      return { text, cfi, sectionIndex: c.index }
    } catch (e) {
      console.warn('selection unreadable', e)
    }
  }
  return null
}

const same = (a: SelectionInfo | null, b: SelectionInfo | null) =>
  a === b || (!!a && !!b && a.text === b.text && a.cfi === b.cfi && a.sectionIndex === b.sectionIndex)

/**
 * Debounced reporter: [changed] on every `selectionchange`; [delayMs] after the last one the selection is read and,
 * if it differs from the last report, sent (null = cleared, only after a non-null report). One message per settle,
 * so a handle drag does not flood the bridge.
 */
export class SelectionReporter {
  private timer: ReturnType<typeof setTimeout> | null = null
  private last: SelectionInfo | null = null

  constructor(
    private readonly read: () => SelectionInfo | null,
    private readonly send: (s: SelectionInfo | null) => void,
    private readonly delayMs = 120,
  ) {}

  /** The selection as last reported. */
  get current(): SelectionInfo | null { return this.last }

  changed() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => this.flush(), this.delayMs)
  }

  private flush() {
    this.timer = null
    let cur: SelectionInfo | null = null
    try { cur = this.read() } catch (e) { console.warn('selection read failed', e) }
    if (same(cur, this.last)) return
    this.last = cur
    this.send(cur)
  }

  /** Forgets the selection and drops a pending report (a new open, a close). */
  reset() {
    if (this.timer) { clearTimeout(this.timer); this.timer = null }
    this.last = null
  }
}
