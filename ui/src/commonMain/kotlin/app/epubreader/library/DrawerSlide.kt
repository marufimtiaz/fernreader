package app.epubreader.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import app.epubreader.reader.contents.FingerHold
import app.epubreader.reader.contents.SlideTrace
import app.epubreader.reader.contents.contentsSlideMs
import app.epubreader.reader.contents.follow
import app.epubreader.reader.contents.panelSwipeCloses
import app.epubreader.ui.Copy
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** C-098: the strip along the edge, in dp from it. */
private const val STRIP_DP = 24f
/** The numbers of C-097's gesture (reader-web/src/reader/edge.ts): the first step is the touch slop, the grid's too. */
private const val FIRST_STEP_DP = 8f
private const val NEXT_STEP_DP = 20f
private const val SIDEWAYS = 0.6f

fun inDrawerStrip(xDp: Float): Boolean = xDp <= STRIP_DP

/**
 * Whether a touch from the strip has started its sideways part, step by step as in C-097. Under 1, because a swipe that gets
 * past the Back detector moved more down than sideways; a finished step that wasn't sideways is left behind, so a long
 * scroll weighs nothing.
 */
class SidewaysWatch(private var x: Float, private var y: Float) {
    private var first = true

    fun sideways(nowX: Float, nowY: Float): Boolean {
        val inward = nowX - x
        val down = abs(nowY - y)
        if (max(abs(inward), down) < if (first) FIRST_STEP_DP else NEXT_STEP_DP) return false
        x = nowX; y = nowY; first = false
        return inward > 0f && inward >= SIDEWAYS * down
    }
}

/** Whether a release finishes the slide it was moving, open or closed: C-097's rule, on the drawer's width. */
fun drawerSwipeFinishes(travelDp: Float, widthDp: Float, speedDpPerMs: Float): Boolean = panelSwipeCloses(travelDp, widthDp, speedDpPerMs)

/**
 * C-098: the library's navigation drawer. ModalNavigationDrawer can't do this one: its drag covers the whole screen, stops
 * for good once the grid has taken the touch, and the drawer's position can't be set from outside.
 *
 * [open] is where it is going, [edgeGesture] whether a gesture from the strip may open it. Every touch from the strip is
 * watched from above the grid, in the Initial pass, and only taken at the sideways part: until then the grid scrolls and
 * taps tap. No gesture exclusion: on gesture navigation the system takes a straight swipe for Back, which arrives here as a
 * cancel and never opens anything. The position changes the placement only, so nothing recomposes while it moves.
 *
 * C-100: the gesture is recognised only after the finger has travelled; a finger takes hold through a [FingerHold], so the
 * drawer comes from the edge and catches up instead of jumping to the finger.
 */
@Composable
fun DrawerSlide(open: Boolean, edgeGesture: Boolean, onOpen: (Boolean) -> Unit, drawer: @Composable () -> Unit, content: @Composable () -> Unit) {
    val shown = remember { Animatable(if (open) 1f else 0f) }
    /** Where a finger holds the drawer (0 = off screen, 1 = open); after a release until [open] has followed it. */
    var held by remember { mutableStateOf<Float?>(null) }
    val width = remember { floatArrayOf(0f) } // px, from the last measure
    val travel = remember { floatArrayOf(0f) }
    val openNow by rememberUpdatedState(open)
    val edgeNow by rememberUpdatedState(edgeGesture && !open)
    val onOpenNow by rememberUpdatedState(onOpen)
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(Unit) {
        var hold: FingerHold? = null
        snapshotFlow { openNow to held }.collectLatest { (o, h) ->
            val to = if (o) 1f else 0f
            if (h == null) hold = null
            if (h != null) shown.follow(h, hold ?: FingerHold(h, shown.value).also { hold = it }) else shown.animateTo(to, tween(contentsSlideMs(shown.value, to), easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(open) { held = null }
    fun settle(wanted: Boolean) { if (wanted == openNow) held = null else onOpenNow(wanted) }
    // Read outside the moving drawer: inside it, the finger's movement would be measured against itself.
    val swipe = rememberDraggableState { delta ->
        travel[0] -= delta
        if (width[0] > 0f) held = 1f - travel[0].coerceIn(0f, width[0]) / width[0]
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    fun inward(c: PointerInputChange) = (if (rtl) size.width - c.position.x else c.position.x) / density
                    if (!edgeNow || !inDrawerStrip(inward(down))) return@awaitEachGesture
                    val watch = SidewaysWatch(inward(down), down.position.y / density)
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) } // a flick has few events: all of them count
                    var following = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val c = event.changes.firstOrNull { it.id == down.id }
                        if (c == null || event.changes.size > 1) { // a second finger ends it
                            if (following) settle(false)
                            return@awaitEachGesture
                        }
                        val widthDp = width[0] / density
                        if (!c.pressed) {
                            if (following) {
                                // a cancel comes in consumed, a lift doesn't: the system's Back must not read as a flick
                                tracker.addPosition(c.uptimeMillis, c.position)
                                val speed = tracker.calculateVelocity().x * (if (rtl) -1f else 1f) / density / 1000f
                                settle(!c.isConsumed && drawerSwipeFinishes(inward(c).coerceIn(0f, widthDp), widthDp, speed))
                                c.consume()
                            }
                            return@awaitEachGesture
                        }
                        tracker.addPosition(c.uptimeMillis, c.position)
                        if (!following) following = watch.sideways(inward(c), c.position.y / density) && edgeNow
                        if (following) {
                            c.consume()
                            if (widthDp > 0f) held = (inward(c) / widthDp).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            .draggable(
                swipe, Orientation.Horizontal, enabled = open, reverseDirection = rtl,
                onDragStarted = { travel[0] = 0f },
                onDragStopped = { velocity -> settle(!drawerSwipeFinishes(travel[0] / density, width[0] / density, -velocity / density / 1000f)) },
            ),
    ) {
        Box { content() }
        val scrim = DrawerDefaults.scrimColor
        val dismissing = if (open) Modifier.pointerInput(Unit) { detectTapGestures { onOpenNow(false) } }.semantics(mergeDescendants = true) {
            contentDescription = Copy.closeNavigationMenu
            onClick { onOpenNow(false); true }
        } else Modifier
        Canvas(Modifier.fillMaxSize().then(dismissing)) { drawRect(scrim, alpha = shown.value) }
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val sheet = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    width[0] = sheet.width.toFloat()
                    layout(sheet.width, sheet.height) {
                        sheet.placeRelative(-((1f - shown.value) * sheet.width).roundToInt(), 0)
                        SlideTrace.sink?.invoke("drawer", shown.value, sheet.width.toFloat())
                    }
                }
                .semantics {
                    paneTitle = Copy.navigationMenu
                    if (open) dismiss { onOpenNow(false); true }
                },
        ) { drawer() }
    }
}
