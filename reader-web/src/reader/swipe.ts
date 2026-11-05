/**
 * Swipe recognition for page turns (F-032). The paginator reports each finished one-finger gesture in paginated flow,
 * and each clearly horizontal one in One at a time scrolling (C-093, a chapter turn), as its finger displacement ([dx],
 * [dy] in CSS px, positive = right / down) and duration [dt] in ms; this decides whether it was a turn and which way.
 * Every recognised swipe is exactly one turn, queued like a bridge turn (C-026). A gesture the page followed (C-094) is
 * decided by [dragFinishes] and is finished by the paginator; only one it could not follow comes here.
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

/**
 * C-094: whether a released drag finishes its turn. [main]: the finger's movement toward the turn, [cross]: across it,
 * [speed]: its speed toward the turn as it lifted (px/ms, negative = coming back), [size]: the page. It finishes past
 * about a third of the page, or as a flick with F-032's numbers; a flick back or a release mostly across the axis does not.
 */
export function dragFinishes({ main, cross, speed, size }: { main: number; cross: number; speed: number; size: number }): boolean {
  if (!(main > 0) || !(size > 0) || !Number.isFinite(speed)) return false
  if (main < DOMINANCE * Math.abs(cross) || speed <= -FLICK_SPEED) return false
  return (main >= FLICK_DISTANCE && speed >= FLICK_SPEED) || main >= size / 3
}

/**
 * C-094: whether the page can follow the finger. The finger's turn direction ([dir], from the book or the swipe setting)
 * has to agree with the way the pages lie ([rtl]): with the setting forced against them, a page going forward moves
 * against the finger, so such a swipe turns on release. Top-to-bottom pages ([vertical]) follow up and down.
 */
export function followsFinger(dir: Dir, rtl: boolean, vertical: boolean): boolean {
  return vertical || (dir === 'rtl') === rtl
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
