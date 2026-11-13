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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import app.epubreader.reader.ReaderController
import app.epubreader.ui.edgeSwipe
import app.epubreader.ui.sectionEnd
import app.epubreader.ui.sectionStart
import app.epubreader.ui.traced
import kotlinx.coroutines.delay
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
 * C-102: the gesture from the right edge (always the right one, also in right-to-left books) that opens the Contents panel,
 * for the page's parent, above the reader's WebView: recognised by the app as the library's drawer is ([edgeSwipe]), so the
 * page gets every touch until the sideways part starts and a cancel then, and turns nothing. The panel follows the finger
 * (not with page animation off: it opens on release) and opens past a third of the width or with a flick. [active]: the
 * reader is the screen shown.
 */
fun Modifier.contentsEdgeSwipe(c: ReaderController, active: () -> Boolean): Modifier = edgeSwipe(
    fromRight = true,
    enabled = { active() && c.edgeSwipeAllowed() },
    onHold = { inDp, widthDp -> if (widthDp > 0f) c.dragContents((inDp / widthDp).coerceIn(0f, 1f)) },
    onEnd = { inDp, widthDp, speed -> c.releaseEdge(inDp != null && edgeSwipeOpens(inDp.coerceIn(0f, widthDp), widthDp, speed)) },
)

/** Whether a release of the edge gesture opens the panel: the panel swipe's rule (a third, or a flick), the drawer's too. */
fun edgeSwipeOpens(travelDp: Float, widthDp: Float, speedDpPerMs: Float): Boolean = panelSwipeCloses(travelDp, widthDp, speedDpPerMs)

/**
 * How long the reader must have stood still (no page turn, no chapter change, nothing over the page) before the Contents
 * panel is got ready ahead of showing. A reader reads a page for far longer; a quick run of turns never pays for it.
 */
const val PREPARE_QUIET_MS = 700L

/**
 * The Contents panel got ready ahead of showing ([ContentsSlide]): which handle holds it and for what [key] (the content's
 * identity: contents list, current entry, title, tab). Kept apart from Compose so the rule can be tested on the host.
 */
class PanelAhead<H : Any>(private val dispose: (H) -> Unit) {
    var handle: H? = null
        private set
    var key: Any? = null
        private set

    /**
     * The panel's state changed: [key] what it would show (null: nothing to get ready, e.g. no book or loading), [visible]
     * whether it is on screen or going there. Returns whether a preparation is due (the caller waits for quiet first). A
     * visible panel belongs to the layout, which took the prepared one over: it is forgotten, not disposed. A stale one
     * (another key) is disposed.
     */
    fun update(key: Any?, visible: Boolean): Boolean {
        if (visible) { handle = null; this.key = null; return false }
        if (handle != null && this.key == key) return false
        handle?.let(dispose)
        handle = null; this.key = null
        return key != null
    }

    fun prepared(key: Any?, h: H) { handle = h; this.key = key }
}

/** For the tests on the device: how many times the Contents panel was composed afresh, and how many of those ahead of showing. */
object ContentsPanelTrace {
    @kotlin.concurrent.Volatile var fresh = 0
    @kotlin.concurrent.Volatile var ahead = 0
}

/** Whether the Contents panel is on screen (or going there); read by what should happen only once it shows (P-031's probe). */
val LocalContentsShown = staticCompositionLocalOf<() -> Boolean> { { true } }

private const val SLOT = "contents"

/**
 * C-097: the Contents panel's way on and off the screen, from and to the right. [open] is where it is going, [drag] where a
 * finger holds it (0..1, null when none does): the edge gesture ([contentsEdgeSwipe]) or, through [onDrag] and [onRelease], a rightward
 * swipe on the panel. The position changes the layer only, so nothing recomposes while it moves. It stays composed until
 * it has left. With [animated] false it just appears and goes.
 *
 * C-100: a finger takes hold through a [FingerHold], so the panel comes from where it was and never jumps to the finger.
 * Nothing is done at a touch-down in the strip (C-100b: composing the panel there put that work on the main thread while
 * the gesture was still being judged, and the edge swipe got harder to start).
 *
 * The panel's first composition and measure (its tabs and the list's visible rows: about 150 ms on the emulator's debug
 * build) used to run in the frame it first showed, so the finger's moves of that time came batched and the panel stood
 * still, then jumped on. Now, while nothing shows and the reader has stood still for [PREPARE_QUIET_MS] ([prepare] the
 * content's identity, null when there's nothing to get ready; [quiet] changes at each turn), the panel is composed and
 * measured ahead in a slot of this layout, unplaced and undrawn; its first frame only places it. A change of [prepare]
 * (another chapter, contents list or tab) gets it ready again; a turn of the page doesn't. Once it has left, it is
 * disposed as before and got ready again at the next quiet moment.
 */
@Composable
fun ContentsSlide(
    open: Boolean, drag: StateFlow<Float?>, animated: Boolean,
    onDrag: (Float) -> Unit, onRelease: (close: Boolean) -> Unit,
    prepare: Any? = null, quiet: Any? = null,
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
    val onDragNow by rememberUpdatedState(onDrag)
    val onReleaseNow by rememberUpdatedState(onRelease)
    // The slot's content: the panel as [content] gives it now. While it waits unplaced it keeps the content it was got ready
    // with (no recomposition at each turn); the layout hands it the current one when it shows.
    val live: @Composable () -> Unit = remember(content) {
        {
            remember { Snapshot.withoutReadObservation { ContentsPanelTrace.fresh++; if (!(openNow || onScreen)) ContentsPanelTrace.ahead++ } }
            SlideBody(shown, { openNow }, { openNow || onScreen }, { onDragNow(it) }, { onReleaseNow(it) }, content)
        }
    }
    val liveNow by rememberUpdatedState(live)
    val prepareNow by rememberUpdatedState(prepare)
    val quietNow by rememberUpdatedState(quiet)
    val sub = remember { SubcomposeLayoutState() }
    val last = remember { LastConstraints() }
    val ahead = remember { PanelAhead<SubcomposeLayoutState.PrecomposedSlotHandle> { it.dispose() } }
    LaunchedEffect(sub) {
        snapshotFlow { Triple(prepareNow, quietNow, openNow || onScreen) }.collectLatest { (key, _, visible) ->
            if (!ahead.update(key, visible)) return@collectLatest
            delay(PREPARE_QUIET_MS)
            withFrameNanos { } // just after a frame: the most time until the next one
            traced("Contents:prepare") {
                val h = sub.precompose(SLOT, liveNow)
                last.constraints?.let { if (h.placeablesCount > 0) h.premeasure(0, it) }
                ahead.prepared(key, h)
            }
        }
    }
    DisposableEffect(sub) { onDispose { ahead.update(null, false) } }
    SubcomposeLayout(sub, Modifier.fillMaxSize()) { constraints ->
        last.constraints = constraints
        if (!(openNow || onScreen)) return@SubcomposeLayout layout(constraints.maxWidth, constraints.maxHeight) {}
        val placeables = subcompose(SLOT, liveNow).map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

private class LastConstraints { var constraints: Constraints? = null }

/** The panel in its moving layer, with the rightward swipe that closes it. [open]: where it is going; [shownNow]: whether it is on screen. */
@Composable
private fun SlideBody(
    shown: Animatable<Float, AnimationVector1D>, open: () -> Boolean, shownNow: () -> Boolean,
    onDrag: (Float) -> Unit, onRelease: (close: Boolean) -> Unit, content: @Composable () -> Unit,
) {
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
                swipe, Orientation.Horizontal, enabled = open(),
                onDragStarted = { travel[0] = 0f },
                onDragStopped = { velocity -> onRelease(panelSwipeCloses(travel[0] / density, width / density, velocity / density / 1000f)) },
            ),
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * (1f - shown.value)
                    SlideTrace.sink?.invoke("contents", shown.value, size.width)
                },
            ) {
                CompositionLocalProvider(LocalContentsShown provides shownNow) {
                    sectionStart("Contents:compose"); content(); sectionEnd()
                }
            }
        }
    }
}
