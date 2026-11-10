package app.epubreader.reader

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.ceil

/**
 * The reader WebView, full size, attached once it exists (after the first frame, P-001).
 *
 * Hot start (P-002): when the app goes to the background while an opaque screen covers the WebView ([covered]), it
 * turns INVISIBLE, so the first frame after the return does not redraw it (the window's surface is new then, and a
 * WebView's first draw costs 100-180 ms on the emulator). It turns VISIBLE again one frame after that first frame,
 * like the pre-warm after the first frame of a cold start. INVISIBLE keeps the layout, so foliate keeps its viewport.
 * While the app is in the foreground it stays VISIBLE: hidden, it would not run animation frames (page turns, `painted`).
 * Coming back straight into the reader (system picker, open-with while running, P-003) takes the same path: the
 * first frame shows the reader's opaque loading screen, and the WebView is revealed one frame later.
 *
 * C-089: while [covered] in the foreground, or while the reader shows its opaque loading screen over it ([loading]), the
 * WebView stays VISIBLE (so the page keeps running and stays laid out) but its alpha is 0, so the render thread skips its
 * draw (a RenderNode with alpha 0 has nothing to draw). Before, its draw (~117 ms on the emulator) ran on every library
 * frame and the first reader frame (the loading screen, P-015) queued behind it. The alpha returns to 1 in the
 * composition that removes the loading screen (the page has painted), so the page replaces it in the same frame.
 * Checked before every draw against the controller's current state, which the loading screen's composition never runs
 * ahead of: the page is drawn no later than the frame that removes the loading screen (no blank frame).
 *
 * C-099: in fullscreen the WebView reaches the top of the screen in both flows and the page keeps the cutout's band itself
 * ([onTopInset], CSS px, a whole number so scroll offsets stay whole): Pages stay below it, Scroll runs under it and comes to
 * rest below it. Its size doesn't depend on the flow, so a mode switch resizes nothing and the page count doesn't change.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReaderSurface(engine: ReaderEngine, covered: Boolean, background: Color, fullscreen: Boolean = false, loading: () -> Boolean = { false }, onTopInset: (Int) -> Unit = {}, pageGestures: Modifier = Modifier) {
    val created by engine.created.collectAsState()
    if (!created) return
    val density = LocalDensity.current
    val topInset = if (fullscreen) ceil(WindowInsets.displayCutout.getTop(density) / density.density).toInt() else 0
    val reportTopInset by rememberUpdatedState(onTopInset)
    LaunchedEffect(topInset) { reportTopInset(topInset) }
    val coveredNow by rememberUpdatedState(covered)
    val loadingNow by rememberUpdatedState(loading)
    // Before every draw of the window (after the frame's composition): drawn only when neither covered nor loading.
    DisposableEffect(engine) {
        val w = engine.webView
        val l = ViewTreeObserver.OnPreDrawListener {
            val a = if (coveredNow || loadingNow()) 0f else 1f
            if (w.alpha != a) w.alpha = a
            true
        }
        w.viewTreeObserver.addOnPreDrawListener(l)
        onDispose { w.viewTreeObserver.removeOnPreDrawListener(l) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val w = engine.webView
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (w.visibility != View.VISIBLE) { w.awaitDraw(); withFrameNanos { }; w.visibility = View.VISIBLE }
                awaitCancellation()
            } finally {
                if (coveredNow) w.visibility = View.INVISIBLE // stopped under an opaque screen
            }
        }
    }
    // The strips around the inset page (status and navigation bar areas) take the page colour (M2 note, F-074).
    Box(Modifier.fillMaxSize().background(background)) {
    // C-102: [pageGestures] (the Contents edge swipe) watch every touch on the page from its parent, ahead of the WebView.
    // The inset is stable: it never follows the bars showing or hiding (with the controls, F-035), so the page does not
    // reflow. Fullscreen: only the display cutout (transient bars draw over the page), whose top the page keeps itself
    // (C-099); otherwise the bars' size.
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(
            if (fullscreen) WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else WindowInsets.systemBarsIgnoringVisibility,
        ).then(pageGestures),
    ) {
    AndroidView(
        factory = {
            engine.webView.also {
                (it.parent as? ViewGroup)?.removeView(it)
                // AndroidView's default WRAP_CONTENT makes WebView lay the page out with a zero-height viewport
                // (it sizes to content): html/body and 100vh would be 0 and foliate would paginate into nothing.
                it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { it.setBackgroundColor(background.toArgb()) },
    )
    }
    }
}

/** Suspends until the window draws its next frame (this view's tree). */
private suspend fun View.awaitDraw() = suspendCancellableCoroutine { c ->
    val l = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            post { viewTreeObserver.removeOnDrawListener(this) } // not allowed during onDraw
            if (c.isActive) c.resume(Unit)
        }
    }
    viewTreeObserver.addOnDrawListener(l)
    c.invokeOnCancellation { post { viewTreeObserver.removeOnDrawListener(l) } }
}
