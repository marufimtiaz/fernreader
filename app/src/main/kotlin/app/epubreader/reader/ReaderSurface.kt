package app.epubreader.reader

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The reader WebView, full size, attached once it exists (after the first frame, P-001).
 *
 * Hot start (P-002): when the app goes to the background while an opaque screen covers the WebView ([covered]), it
 * turns INVISIBLE, so the first frame after the return does not redraw it (the window's surface is new then, and a
 * WebView's first draw costs 100-180 ms on the emulator). It turns VISIBLE again one frame after that first frame,
 * like the pre-warm after the first frame of a cold start. INVISIBLE keeps the layout, so foliate keeps its viewport.
 * While the app is in the foreground it stays VISIBLE: hidden, it would not run animation frames (page turns, `painted`).
 */
@Composable
fun ReaderSurface(engine: ReaderEngine, covered: Boolean) {
    val created by engine.created.collectAsState()
    if (!created) return
    val coveredNow by rememberUpdatedState(covered)
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
    AndroidView(
        factory = {
            engine.webView.also {
                (it.parent as? ViewGroup)?.removeView(it)
                // AndroidView's default WRAP_CONTENT makes WebView lay the page out with a zero-height viewport
                // (it sizes to content): html/body and 100vh would be 0 and foliate would paginate into nothing.
                it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        // Book content stays clear of the status and gesture bars (edge-to-edge); chrome is drawn over it, so toggling it never reflows.
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
    )
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
