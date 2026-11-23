package app.fernreader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.max

/** C-098, C-102: the strip along the edge, in dp from it. */
private const val STRIP_DP = 24f
/** The first step is the touch slop, the grid's and the page's too. */
private const val FIRST_STEP_DP = 8f
/** Later ones are judged over this distance, so that the jitter of a slow scroll isn't read as a turn. */
private const val NEXT_STEP_DP = 20f
/**
 * A step's inward part against its vertical part. Under 1, because a swipe that gets past the Back detector moved more down
 * than sideways; over the drift of a scrolling thumb.
 */
private const val SIDEWAYS = 0.6f
/**
 * C-101: a natural angled swipe goes two or three times as far down as in, so no step of it is at [SIDEWAYS]. Measured from
 * the touch-down instead, it counts once it is this far in (dp; the strip's width, past any jitter)...
 */
private const val ANGLED_IN_DP = 24f
/** ...and its inward part is at least this share of its vertical one: about 17 degrees off vertical, past a thumb's drift. */
private const val ANGLED = 0.3f

/** Whether a touch [inDp] from the edge is in the strip. */
fun inEdgeStrip(inDp: Float): Boolean = inDp <= STRIP_DP

/**
 * Whether a touch from the strip has started its sideways part, from where it went down (dp from the edge, and down). Two
 * ways: a step that goes clearly inward (a finished step that wasn't is left behind, so a long scroll weighs nothing), or,
 * from the touch-down, enough of an inward angle (C-101), which a scroll within about 15 degrees of vertical never has.
 */
class SidewaysWatch(x: Float, y: Float) {
    private val x0 = x
    private val y0 = y
    private var x = x
    private var y = y
    /** No step has finished yet: the finger hasn't left the touch slop. */
    var still = true
        private set

    fun sideways(nowX: Float, nowY: Float): Boolean {
        val angledIn = nowX - x0
        if (angledIn >= ANGLED_IN_DP && angledIn >= ANGLED * abs(nowY - y0)) return true
        val inward = nowX - x
        val down = abs(nowY - y)
        if (max(abs(inward), down) < if (still) FIRST_STEP_DP else NEXT_STEP_DP) return false
        x = nowX; y = nowY; still = false
        return inward > 0f && inward >= SIDEWAYS * down
    }
}

/**
 * The finger's speed along x at the lift (px/s), from every position the system reported. Input comes batched to the
 * frame: while the main thread is busy (the panel's first frames) one change brings the moves of the wait in its
 * `historical`. Its latest position alone left a gap the tracker reads as a stop, so a flick read as speed 0.
 */
internal class FlickTracker {
    private val tracker = VelocityTracker()
    fun add(c: PointerInputChange) {
        for (h in c.historical) tracker.addPosition(h.uptimeMillis, h.position)
        tracker.addPosition(c.uptimeMillis, c.position)
    }
    fun xVelocity(): Float = tracker.calculateVelocity().x
}

/**
 * C-098, C-102: a one-finger gesture that starts in the strip along this element's right edge ([fromRight]) or its left one
 * and travels inward. It is recognised here, above the content: every touch from the strip is watched in the Initial pass and
 * taken only at its sideways part ([SidewaysWatch]). Until then the content gets all of it, so taps tap, scrolls scroll and a
 * long press is the content's; a touch held still in the strip for a long press stays the content's to the end (a text
 * selection's drag). From the sideways part on every change is consumed, which a child sees as a cancel (an AndroidView's
 * view gets ACTION_CANCEL), and [onHold] is told how far in the finger is (dp) of the element's width (dp).
 *
 * [onEnd]: the lift, with how far in the finger was and its inward speed (dp/ms), or null for the distance when the gesture
 * was taken away (a second finger, or the system's Back, which arrives as a cancel). No gesture exclusion: on gesture
 * navigation the system takes a straight swipe for Back. [enabled] is asked at the touch-down and at the sideways part.
 */
fun Modifier.edgeSwipe(
    fromRight: Boolean,
    enabled: () -> Boolean,
    onHold: (inDp: Float, widthDp: Float) -> Unit,
    onEnd: (inDp: Float?, widthDp: Float, speedDpPerMs: Float) -> Unit,
): Modifier = pointerInput(fromRight) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        fun inward(c: PointerInputChange) = (if (fromRight) size.width - c.position.x else c.position.x) / density
        if (!enabled() || !inEdgeStrip(inward(down))) return@awaitEachGesture
        val watch = SidewaysWatch(inward(down), down.position.y / density)
        val tracker = FlickTracker().apply { add(down) } // a flick has few events: all of them count
        var following = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val c = event.changes.firstOrNull { it.id == down.id }
            val widthDp = size.width / density
            if (c == null || event.changes.size > 1) { // a second finger ends it
                if (following) onEnd(null, widthDp, 0f)
                return@awaitEachGesture
            }
            if (!c.pressed) {
                if (following) {
                    // a cancel comes in consumed, a lift doesn't: the system's Back must not read as a flick
                    tracker.add(c)
                    val speed = tracker.xVelocity() * (if (fromRight) -1f else 1f) / density / 1000f
                    onEnd(if (c.isConsumed) null else inward(c), widthDp, speed)
                    c.consume()
                }
                return@awaitEachGesture
            }
            tracker.add(c)
            if (!following) {
                if (watch.still && c.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                following = watch.sideways(inward(c), c.position.y / density) && enabled()
            }
            if (following) {
                c.consume()
                onHold(inward(c), widthDp)
            }
        }
    }
}
