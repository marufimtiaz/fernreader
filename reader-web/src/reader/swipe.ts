/**
 * Swipe recognition for page turns (F-032). The paginator reports each finished one-finger gesture in paginated flow as
 * its finger displacement ([dx], [dy] in CSS px, positive = right / down) and duration [dt] in ms; this decides whether
 * it was a page turn and which way. Every recognised swipe is exactly one turn, queued like a bridge turn (C-026).
 */

export type Dir = 'ltr' | 'rtl'
export type SwipeSetting = 'auto' | 'ltr' | 'rtl'

/** Movement that is always a swipe, however slow (a drag of about a finger width). */
const MIN_DISTANCE = 48
/** A shorter movement is a swipe when it is a flick: at least this far and this fast (px/ms). */
const FLICK_DISTANCE = 24
const FLICK_SPEED = 0.3
/** The main axis must clearly dominate (a diagonal is not a turn). */
const DOMINANCE = 1.5

/**
 * 'next', 'prev' or null (a tap, a long press, a cancelled drag, a mostly cross-axis move).
 * Horizontal: [dir] ltr = finger moving left is next; rtl = finger moving right is next.
 * [vertical] (vertical writing, which foliate pages top to bottom): an upward swipe is next too.
 */
export function swipeToTurn(dx: number, dy: number, dt: number, dir: Dir, vertical = false): 'next' | 'prev' | null {
  if (!Number.isFinite(dx) || !Number.isFinite(dy)) return null
  const t = Number.isFinite(dt) && dt > 0 ? dt : 0
  const isSwipe = (main: number, cross: number) => {
    const d = Math.abs(main)
    if (d < DOMINANCE * Math.abs(cross)) return false
    return d >= MIN_DISTANCE || (d >= FLICK_DISTANCE && t > 0 && d / t >= FLICK_SPEED)
  }
  if (isSwipe(dx, dy)) return (dir === 'rtl' ? dx > 0 : dx < 0) ? 'next' : 'prev'
  if (vertical && isSwipe(dy, dx)) return dy < 0 ? 'next' : 'prev'
  return null
}

/** The swipe direction in effect: the book's page-progression-direction for 'auto' (or no setting), else the forced one. */
export function effectiveDir(setting: SwipeSetting | undefined, bookDir: string | undefined): Dir {
  if (setting === 'ltr' || setting === 'rtl') return setting
  return bookDir === 'rtl' ? 'rtl' : 'ltr'
}

/**
 * For a renderer that reports no swipes itself (foliate's fixed-layout renderer has no touch handling): a finished
 * one-finger, unzoomed gesture on [source] (a page document or the renderer) is dispatched on [target] as the same
 * `swipe` event `{dx, dy, dt}` the paginator sends with `host-swipe`, so FXL pages turn through the same queue.
 */
export function reportSwipes(source: EventTarget, target: EventTarget) {
  let s: { x: number; y: number; t: number; multi: boolean } | null = null
  source.addEventListener('touchstart', (ev: Event) => {
    const e = ev as TouchEvent
    const t = e.changedTouches?.[0]
    s = t ? { x: t.screenX, y: t.screenY, t: e.timeStamp, multi: (e.touches?.length ?? 1) > 1 } : null
  }, { passive: true })
  source.addEventListener('touchmove', (ev: Event) => {
    if (s && ((ev as TouchEvent).touches?.length ?? 0) > 1) s.multi = true
  }, { passive: true })
  source.addEventListener('touchend', (ev: Event) => {
    const e = ev as TouchEvent
    const t = e.changedTouches?.[0]
    const st = s
    s = null
    if (!st || !t || st.multi || e.touches?.length || (globalThis.visualViewport?.scale ?? 1) > 1) return
    target.dispatchEvent(new CustomEvent('swipe', { detail: { dx: t.screenX - st.x, dy: t.screenY - st.y, dt: e.timeStamp - st.t } }))
  })
}
