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
