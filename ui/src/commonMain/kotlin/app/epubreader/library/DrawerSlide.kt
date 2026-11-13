package app.epubreader.library

import app.epubreader.ui.sectionEnd
import app.epubreader.ui.sectionStart
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.ui.input.pointer.pointerInput
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
import app.epubreader.ui.edgeSwipe
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** Whether a release finishes the slide it was moving, open or closed: C-097's rule, on the drawer's width. */
fun drawerSwipeFinishes(travelDp: Float, widthDp: Float, speedDpPerMs: Float): Boolean = panelSwipeCloses(travelDp, widthDp, speedDpPerMs)

/**
 * C-098: the library's navigation drawer. ModalNavigationDrawer can't do this one: its drag covers the whole screen, stops
 * for good once the grid has taken the touch, and the drawer's position can't be set from outside.
 *
 * [open] is where it is going, [edgeGesture] whether a gesture from the strip may open it ([edgeSwipe], shared with the
 * reader's Contents, C-102: until its sideways part the grid scrolls and taps tap; the system's Back takes a straight swipe on
 * gesture navigation and never opens anything). The position changes the placement only, so nothing recomposes while it moves.
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
            .edgeSwipe(
                fromRight = rtl,
                enabled = { edgeNow },
                onHold = { inDp, _ -> val w = width[0] / density; if (w > 0f) held = (inDp / w).coerceIn(0f, 1f) },
                onEnd = { inDp, _, speed -> val w = width[0] / density; settle(inDp != null && drawerSwipeFinishes(inDp.coerceIn(0f, w), w, speed)) },
            )
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
        ) { sectionStart("Drawer:compose"); drawer(); sectionEnd() }
    }
}
