package app.epubreader.reader

import android.content.pm.ActivityInfo
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.ReaderSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** What the activity's window should look like (F-035). */
data class ReaderWindowState(val barsHidden: Boolean, val keepScreenOn: Boolean, val orientation: Int)

/**
 * The part of the reader state the window depends on. A page turn (location, chapter, marker) changes none of it, so
 * the activity maps the controller's state to this with distinctUntilChanged and neither recomposes nor touches the
 * window on a turn (P-026, R-005).
 */
data class ReaderWindowInputs(
    /** A book's pages are on screen: open, loaded, no error screen. */
    val bookShown: Boolean,
    /** The open-failure or R-008 screen is shown. */
    val error: Boolean,
    val chromeVisible: Boolean,
) {
    companion object {
        fun of(ui: ReaderUiState) = ReaderWindowInputs(ui.open && ui.error == null && !ui.loading, ui.error != null, ui.chromeVisible)
    }
}

/**
 * Screen behaviour from (route, reader state, settings). Only the reader route changes anything; the library follows
 * the system. Fullscreen hides the system bars while the controls are hidden and shows them with the controls (the
 * page is inset by a stable amount, so neither reflows it) and on an error screen. Keep-screen-on needs a book on
 * screen. An orientation lock follows the sensor within the locked orientation (either way up).
 */
fun readerWindowState(inReader: Boolean, r: ReaderWindowInputs, s: ReaderSettings): ReaderWindowState =
    if (!inReader) ReaderWindowState(false, false, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    else ReaderWindowState(
        barsHidden = s.fullscreen && !r.chromeVisible && !r.error,
        keepScreenOn = s.keepScreenOn && r.bookShown,
        orientation = when (s.orientation) {
            OrientationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        },
    )

/** Everything the activity sets on its window for the reader: brightness (or BRIGHTNESS_OVERRIDE_NONE), bar icons, [win]. */
data class WindowLook(val brightness: Float, val lightBars: Boolean, val win: ReaderWindowState)

/** Calls [apply] only when the look differs from the one applied last; [reapply] repeats the last one (back from another app). */
class WindowApplier(private val apply: (WindowLook) -> Unit) {
    private var last: WindowLook? = null
    fun update(look: WindowLook) {
        if (look == last) return
        last = look
        apply(look)
    }
    fun reapply() { last?.let(apply) }
}

/**
 * True once the reader route has been on screen for a frame (P-015): the reader's window look waits for it, so the
 * frame after the reader's first one is not made to draw (and wait for the render thread) by a window change.
 * False again as soon as the route is left.
 */
@androidx.compose.runtime.Composable
fun rememberReaderShown(inReader: Boolean): Boolean {
    var framed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(inReader) {
        framed = false
        if (inReader) { androidx.compose.runtime.withFrameNanos { }; framed = true }
    }
    return readerWindowActive(inReader, framed)
}

/** The reader's window look applies only on the reader route, after its first frame ([rememberReaderShown]). */
fun readerWindowActive(inReader: Boolean, readerFramed: Boolean): Boolean = inReader && readerFramed
