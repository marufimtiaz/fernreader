package app.fernreader

import android.app.Instrumentation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import app.fernreader.reader.contents.SlideTrace
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * C-100: the frames of a slide (`contents` or `drawer`, as [SlideTrace] names them): for each frame that gave the panel a new
 * place, the time and how far its leading edge is in from the screen edge it comes from (px). The finger is injected here
 * too, at a steady [STEP_PX] a step, so that "the finger's movement in a frame" is known: at most two steps.
 */
class SlideFrames(private val inst: Instrumentation, private val name: String) {
    data class Frame(val t: Long, val edge: Float)
    data class Touch(val t: Long, val x: Int)

    val frames = CopyOnWriteArrayList<Frame>()
    val finger = mutableListOf<Touch>()

    fun record() { SlideTrace.sink = { n, shown, width -> if (n == name) frames += Frame(SystemClock.uptimeMillis(), shown * width) } }
    fun stop() { SlideTrace.sink = null }

    /** One finger from [fromX] by [steps] steps of [STEP_PX] ([dir] -1 leftward, 1 rightward), then still for [holdMs]; lifted after. */
    suspend fun drag(fromX: Int, y: Int, dir: Int, steps: Int = 40, holdMs: Long = 400) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Int) {
            val now = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, now, action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
            finger += Touch(now, x)
        }
        var x = fromX
        send(MotionEvent.ACTION_DOWN, x)
        try {
            delay(STEP_MS)
            repeat(steps) { x += dir * STEP_PX; send(MotionEvent.ACTION_MOVE, x); delay(STEP_MS) }
            delay(holdMs)
        } finally {
            stop()
            send(MotionEvent.ACTION_UP, x)
        }
    }

    /**
     * The spec's test: from its first visible frame the edge increases smoothly from the screen edge. The first visible frame
     * moved it no further than the finger moves in a frame, it never goes back while the finger goes on, it is never ahead
     * of the finger, and it has caught up with the finger once the finger is still. [travel] turns a finger's x into its
     * distance from the panel's screen edge.
     */
    fun assertSmoothFromTheEdge(travel: (Int) -> Int) {
        val seen = frames.filter { it.edge > 0.5f }
        val trace = seen.joinToString(" ") { "%.0f".format(it.edge) }
        assertTrue("the panel came out: $trace", seen.size >= 5)
        val perFrame = 2 * STEP_PX
        assertTrue("first visible frame at ${seen.first().edge} px, the finger moves at most $perFrame px a frame: $trace", seen.first().edge <= perFrame + 1)
        seen.zipWithNext().forEach { (a, b) -> assertTrue("never back: ${a.edge} -> ${b.edge} in $trace", b.edge >= a.edge - 0.5f) }
        for (f in seen) {
            val at = finger.last { it.t <= f.t }
            assertTrue("never ahead of the finger: ${f.edge} px, the finger ${travel(at.x)} px in: $trace", f.edge <= travel(at.x) + 1)
        }
        val end = travel(finger.last().x)
        assertTrue("caught up: ${seen.last().edge} px, the finger $end px in: $trace", seen.last().edge >= end - 2)
    }

    /** The button's slide: from the edge, a small first step, never back. */
    fun assertSlidesInFromTheEdge(width: Int) {
        val seen = frames.filter { it.edge > 0.5f }
        val trace = seen.joinToString(" ") { "%.0f".format(it.edge) }
        assertTrue("the panel came in over several frames: $trace", seen.size >= 5)
        assertTrue("first visible frame at ${seen.first().edge} px: $trace", seen.first().edge <= width / 20f)
        seen.zipWithNext().forEach { (a, b) -> assertTrue("never back: ${a.edge} -> ${b.edge} in $trace", b.edge >= a.edge - 0.5f) }
        assertTrue("open: $trace", seen.last().edge >= width - 1)
    }

    companion object {
        const val STEP_PX = 6
        const val STEP_MS = 16L
    }
}
