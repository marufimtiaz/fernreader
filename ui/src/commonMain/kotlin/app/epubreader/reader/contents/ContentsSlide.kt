package app.epubreader.reader.contents

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlin.math.abs

/** C-097: the whole slide. */
const val CONTENTS_SLIDE_MS = 300
private const val MIN_SLIDE_MS = 60
/** F-032's flick: at least this far (dp) and this fast (dp/ms). */
private const val FLICK_DP = 24f
private const val FLICK_SPEED = 0.3f

/** How long the panel takes between two positions (0 = off screen, 1 = open): the rest of a slide takes its share of the time. */
fun contentsSlideMs(from: Float, to: Float): Int = (CONTENTS_SLIDE_MS * abs(to - from)).toInt().coerceIn(MIN_SLIDE_MS, CONTENTS_SLIDE_MS)

/** C-100: how long a panel takes to catch up with a finger that took hold of it ahead of it. */
const val CATCH_UP_MS = 120

/** The share of the lag still left [elapsedMs] after the catch-up began: slow at first, so the panel starts from rest. */
fun catchUpLeft(elapsedMs: Float): Float =
    if (elapsedMs >= CATCH_UP_MS) 0f else 1f - FastOutSlowInEasing.transform((elapsedMs / CATCH_UP_MS).coerceAtLeast(0f))

/**
 * C-100: a finger took hold of a slide at [finger] while the panel was at [shown] (0 = off screen, 1 = open). The gesture is
 * recognised only after the finger has travelled, so the two differ; the panel is not put under the finger at once but keeps
 * that lag, then takes it up over [CATCH_UP_MS] from the first frame on. Its first frame moves only as far as the finger did.
 */
class FingerHold(finger: Float, shown: Float) {
    private val lag = finger - shown
    private var start = -1L
    private var left = 1f
    val caughtUp get() = left == 0f

    /** Where the panel is with the finger at [finger]; [frameNanos], the frame's time, moves the catch-up on. */
    fun at(finger: Float, frameNanos: Long? = null): Float {
        if (frameNanos != null) {
            if (start < 0) start = frameNanos
            left = catchUpLeft((frameNanos - start) / 1e6f)
        }
        return (finger - lag * left).coerceIn(0f, 1f)
    }
}

/** Holds the slide at the finger, less what is left of the hold's lag, frame by frame until the lag has gone. */
suspend fun Animatable<Float, AnimationVector1D>.follow(finger: Float, hold: FingerHold) {
    snapTo(hold.at(finger))
    while (!hold.caughtUp) snapTo(withFrameNanos { hold.at(finger, it) })
}

/**
 * C-100, for the tests on the device: told of a slide's position ([name], 0..1, of its width in px) each time a frame takes a new one. Null in
 * the app.
 */
object SlideTrace {
    @kotlin.concurrent.Volatile var sink: ((name: String, shown: Float, widthPx: Float) -> Unit)? = null
}

/** Whether a rightward swipe on the panel closes it: past about a third of the width or a flick; a flick back never. */
fun panelSwipeCloses(travelDp: Float, widthDp: Float, speedDpPerMs: Float): Boolean =
    travelDp > 0f && widthDp > 0f && speedDpPerMs > -FLICK_SPEED &&
        ((travelDp >= FLICK_DP && speedDpPerMs >= FLICK_SPEED) || travelDp >= widthDp / 3f)

/**
 * C-097: the Contents panel's way on and off the screen, from and to the right. [open] is where it is going, [drag] where a
 * finger holds it (0..1, null when none does): the page's edge gesture or, through [onDrag] and [onRelease], a rightward
 * swipe on the panel. The position changes the layer only, so nothing recomposes while it moves. It stays composed until
 * it has left. With [animated] false it just appears and goes.
 *
 * C-100: a finger takes hold through a [FingerHold], so the panel comes from where it was and never jumps to the finger.
 * The panel is composed only when it is to show, not ahead of it: composing it at the touch-down in the strip put that
 * work on the main thread while the page was still judging the gesture, and the edge swipe got harder to start.
 */
@Composable
fun ContentsSlide(
    open: Boolean, drag: StateFlow<Float?>, animated: Boolean,
    onDrag: (Float) -> Unit, onRelease: (close: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val shown = remember { Animatable(0f) }
    val openNow by rememberUpdatedState(open)
    val animatedNow by rememberUpdatedState(animated)
    LaunchedEffect(drag) {
        var hold: FingerHold? = null
        combine(snapshotFlow { openNow }, drag) { o, d -> o to d }.collectLatest { (o, d) ->
            val to = if (o) 1f else 0f
            if (d == null) hold = null
            when {
                d != null -> shown.follow(d, hold ?: FingerHold(d, shown.value).also { hold = it })
                animatedNow -> shown.animateTo(to, tween(contentsSlideMs(shown.value, to), easing = FastOutSlowInEasing))
                else -> shown.snapTo(to)
            }
        }
    }
    val onScreen by remember { derivedStateOf { shown.value > 0f } }
    if (!open && !onScreen) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val density = LocalDensity.current.density
        val travel = remember { floatArrayOf(0f) }
        // The swipe is read outside the moving layer: inside it, the finger's movement would be measured against itself.
        val swipe = rememberDraggableState { delta ->
            travel[0] += delta
            if (width > 0f) onDrag(1f - travel[0].coerceIn(0f, width) / width)
        }
        Box(
            Modifier.fillMaxSize().draggable(
                swipe, Orientation.Horizontal, enabled = open,
                onDragStarted = { travel[0] = 0f },
                onDragStopped = { velocity -> onRelease(panelSwipeCloses(travel[0] / density, width / density, velocity / density / 1000f)) },
            ),
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * (1f - shown.value)
                    SlideTrace.sink?.invoke("contents", shown.value, size.width)
                },
            ) { content() }
        }
    }
}
