import type { FromWeb, ToWeb } from '../src/bridge/protocol'
import { DEFAULT_SETTINGS, type WebSettings } from '../src/reader/styles'
import { DEFAULT_CHAPTER_TEXTS } from '../src/reader/chapters'

/** Copy.missingPart in the app. */
const MISSING_PART = "This part of the book is missing from the file, so it can't be shown. You can keep reading."

/** PageTurnInput.kt's tapAction with tap zones on. */
export function tapAction(x: number, rtl: boolean): 'next' | 'prev' | 'controls' | 'none' {
  if (x >= 0.33 && x <= 0.67) return 'controls'
  if (x < 0.25) return rtl ? 'next' : 'prev'
  if (x > 0.75) return rtl ? 'prev' : 'next'
  return 'none'
}

export type HostStore = { get(key: string): string | null; set(key: string, value: string): void }

/**
 * The part of the app the reader page talks to (ReaderController, in miniature): it opens a book when the page says
 * `ready`, keeps the position and the settings, and turns pages on taps. The page reloads on every edit, so `ready`
 * comes again and the book reopens where it was.
 */
export class FakeHost {
  book: string | null = null
  settings: WebSettings
  private ready = false
  private dir: 'ltr' | 'rtl' = 'ltr'

  constructor(private send: (m: ToWeb) => void, private show: (m: FromWeb) => void, private store: HostStore) {
    this.settings = { ...DEFAULT_SETTINGS, ...JSON.parse(store.get('settings') ?? '{}') }
  }

  fromWeb(json: string) {
    let m: FromWeb
    try { m = JSON.parse(json) } catch { console.warn('dev host: not JSON', json); return }
    if (m.type === 'ready') { this.ready = true; this.open() }
    if (m.type === 'opened') this.dir = m.dir
    if (m.type === 'relocate') this.store.set(`position:${m.sessionId}`, m.cfi)
    if (m.type === 'tap') {
      const a = tapAction(m.x, this.dir === 'rtl')
      if (a === 'next' || a === 'prev') this.command({ v: 1, type: a })
    }
    this.show(m)
  }

  /** A message for the reader, dropped until the reader page is up. */
  command(m: ToWeb) { if (this.ready) this.send(m) }

  changeSettings(change: Partial<WebSettings>) {
    this.settings = { ...this.settings, ...change }
    this.store.set('settings', JSON.stringify(this.settings))
    this.command({ v: 1, type: 'setSettings', settings: this.settings })
  }

  openBook(id: string) {
    this.command({ v: 1, type: 'close' })
    this.book = id
    this.open()
  }

  forgetPosition() { if (this.book) this.store.set(`position:${this.book}`, '') }

  private open() {
    if (!this.book || !this.ready) return
    this.dir = 'ltr'
    this.send({
      v: 1, type: 'open', sessionId: this.book, position: this.store.get(`position:${this.book}`) || null,
      texts: { missingPart: MISSING_PART, ...DEFAULT_CHAPTER_TEXTS }, settings: this.settings,
    })
  }
}
