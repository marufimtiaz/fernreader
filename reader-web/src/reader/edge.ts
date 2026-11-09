/**
 * C-097: the Contents panel comes in from the right edge. A one-finger gesture that starts in a strip along the right edge
 * (always the right one, also in right-to-left books) and then travels left is reported to the app as `start`, `move`,
 * `end`; the app moves the panel. No gesture exclusion: on gesture navigation the system takes a straight inward swipe
 * for Back (a `touchcancel` here) and leaves a gesture that moved more down than sideways past its slop to the app, so
 * every touch from the strip is watched, and the sideways part may start at any time: a step that goes clearly sideways
 * ([edgeStep]) or, from the touch-down, enough of a leftward angle ([edgeAngled], C-100c). From its first touch such a gesture
 * belongs here: it never turns a page or changes chapter. A tap makes no moves, so it stays a tap.
 */
import { dragFinishes } from './swipe'

/** The strip's width (CSS px, which are dp in the WebView). */
export const EDGE_STRIP = 24
/** The first move a page sees is past Chromium's touch slop (8 dp), and only it can still stop the native scroll. */
const FIRST_STEP = 8
/** Later ones are judged over this distance, so that the jitter of a slow scroll isn't read as a turn. */
const NEXT_STEP = 20
/**
 * The leftward part of a step against its vertical part. Under 1, because a swipe that gets past the Back detector moved
 * more down than sideways; over the drift of a scrolling thumb.
 */
const SIDEWAYS = 0.6
/**
 * C-100c: a natural angled swipe goes two or three times as far down as left, so no step of it is at [SIDEWAYS]. Measured
 * from the touch-down instead, it counts once it is this far left (dp; the strip's width, past any jitter)...
 */
const ANGLED_LEFT = 24
/** ...and its leftward part is at least this share of its vertical one: about 17 degrees off vertical, past a thumb's drift. */
const ANGLED = 0.3
const SPEED_WINDOW = 100

export const inEdgeStrip = (x: number, width: number) => width > 0 && x >= width - EDGE_STRIP

export type Anchor = { x: number; y: number; first: boolean }

/**
 * Whether the sideways part has started, from where the finger is now ([x], [y]) and [anchor], where the step being
 * judged began. `anchor` is the next one: a finished step that wasn't sideways is left behind, so a long scroll weighs nothing.
 */
export function edgeStep(anchor: Anchor, x: number, y: number): { anchor: Anchor; sideways: boolean } {
  const left = anchor.x - x, down = Math.abs(y - anchor.y)
  if (Math.max(Math.abs(left), down) < (anchor.first ? FIRST_STEP : NEXT_STEP)) return { anchor, sideways: false }
  return { anchor: { x, y, first: false }, sideways: left > 0 && left >= SIDEWAYS * down }
}

/**
 * C-100c: whether the gesture that went down at [from] has, with the finger at ([x], [y]), gone far enough left at an angle that
 * isn't nearly vertical. From the touch-down, so a long scroll weighs against it; one that turns sideways later is [edgeStep]'s.
 */
export function edgeAngled(from: { x: number; y: number }, x: number, y: number): boolean {
  const left = from.x - x
  return left >= ANGLED_LEFT && left >= ANGLED * Math.abs(y - from.y)
}

/** The share of the screen the panel covers with its edge under the finger. */
export const edgeFraction = (x: number, width: number) => width > 0 ? Math.min(1, Math.max(0, (width - x) / width)) : 0

/** Whether a release opens the panel: past about a third of the width or a flick, with F-032's numbers. [speed]: leftward, px/ms. */
export const edgeOpens = (fraction: number, speed: number, width: number) =>
  dragFinishes({ main: fraction * width, cross: 0, speed, size: width })

type Sample = { d: number; t: number }
/** How fast the finger went left just before it lifted (px/ms). */
export function edgeSpeed(trail: Sample[]): number {
  const last = trail.at(-1)
  if (!last) return 0
  const from = trail.filter(s => s.t <= last.t - SPEED_WINDOW).at(-1) ?? trail[0]
  return last.t > from.t ? (last.d - from.d) / (last.t - from.t) : 0
}

/** Where [t] is in the host page's viewport, for a touch in a section's frame too (scaled in fixed-layout books). */
export function viewportPoint(t: Touch): { x: number; y: number } {
  const frame = (t.target as Node | null)?.ownerDocument?.defaultView?.frameElement as HTMLElement | null | undefined
  if (!frame) return { x: t.clientX, y: t.clientY }
  const box = frame.getBoundingClientRect()
  const k = frame.offsetWidth ? box.width / frame.offsetWidth : 1
  return { x: box.left + t.clientX * k, y: box.top + t.clientY * k }
}

export type EdgePhase = 'start' | 'move' | 'end'
/** What a move is to the touch handlers: not ours (null), watched from the strip, or moving the panel. */
export type EdgeMove = 'watching' | 'following' | null

type Gesture = {
  /** From screen coordinates, which stay put when a frame scrolls or moves under the finger, to the viewport's. */
  ox: number; oy: number
  /** Where it went down (viewport). */
  from: { x: number; y: number }
  anchor: Anchor
  following: boolean
  /** Text is being selected: the gesture stays out of the panel's way and still turns nothing. */
  off: boolean
  fraction: number
  trail: Sample[]
}

/** One touch sequence from the strip. The renderer's touch handlers call it; [report] goes to the app. */
export class EdgeGesture {
  private g: Gesture | null = null
  /** For tests: what was last reported. */
  last: { phase: EdgePhase; fraction: number; open: boolean; starts: number } = { phase: 'end', fraction: 0, open: false, starts: 0 }

  constructor(private report: (phase: EdgePhase, fraction: number, open: boolean) => void) {}

  private send(phase: EdgePhase, fraction: number, open: boolean) {
    this.last = { phase, fraction, open, starts: this.last.starts + (phase === 'start' ? 1 : 0) }
    this.report(phase, fraction, open)
  }

  private point(g: Gesture, t: Touch) { return { x: t.screenX + g.ox, y: t.screenY + g.oy } }

  /** A touchstart. True when it begins a gesture from the strip; a second finger ends the running one. */
  down(e: TouchEvent): boolean {
    this.drop()
    const t = e.changedTouches?.[0]
    if (!t || e.touches?.length > 1 || (globalThis.visualViewport?.scale ?? 1) > 1) return false
    const p = viewportPoint(t)
    if (!inEdgeStrip(p.x, window.innerWidth)) return false
    this.g = {
      ox: p.x - t.screenX, oy: p.y - t.screenY, from: { x: p.x, y: p.y }, anchor: { x: p.x, y: p.y, first: true },
      following: false, off: false, fraction: 0, trail: [],
    }
    return true
  }

  /** A touchmove. Null when it isn't this gesture's (none is running, or a second finger or a pinch has just ended it). */
  move(e: TouchEvent): EdgeMove {
    const g = this.g
    if (!g) return null
    const t = e.changedTouches?.[0]
    if (!t || e.touches?.length > 1 || (globalThis.visualViewport?.scale ?? 1) > 1) { this.drop(); return null }
    if (g.off) return 'watching'
    const { x, y } = this.point(g, t), width = window.innerWidth
    if (!g.following) {
      const step = edgeStep(g.anchor, x, y)
      g.anchor = step.anchor
      if (!step.sideways && !edgeAngled(g.from, x, y)) return 'watching'
      const sel = (t.target as Node | null)?.ownerDocument?.getSelection?.()
      if (sel && !sel.isCollapsed) { g.off = true; return 'watching' }
      g.following = true
    }
    const started = !g.trail.length
    g.fraction = edgeFraction(x, width)
    g.trail.push({ d: -x, t: e.timeStamp })
    this.send(started ? 'start' : 'move', g.fraction, false)
    return 'following'
  }

  /** A touchend. True when it ended a gesture from the strip: nothing else may act on it. */
  up(e: TouchEvent): boolean {
    const g = this.g
    if (!g) return false
    this.g = null
    if (!g.following) return true
    const t = e.changedTouches?.[0]
    if (t) {
      const { x } = this.point(g, t)
      g.fraction = edgeFraction(x, window.innerWidth)
      g.trail.push({ d: -x, t: e.timeStamp })
    }
    this.send('end', g.fraction, edgeOpens(g.fraction, edgeSpeed(g.trail), window.innerWidth))
    return true
  }

  /** The gesture is over without a release (`touchcancel`, a second finger, the book closing): the panel goes back. */
  drop() {
    const g = this.g
    this.g = null
    if (g?.following) this.send('end', g.fraction, false)
  }
}
