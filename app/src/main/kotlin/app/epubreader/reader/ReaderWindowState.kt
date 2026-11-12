package app.epubreader.reader

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areStatusBarsVisible
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
    /** C-104: the user swiped the bars in, without the controls. */
    val barsRevealed: Boolean = false,
) {
    companion object {
        fun of(ui: ReaderUiState) = ReaderWindowInputs(ui.open && ui.error == null && !ui.loading, ui.error != null, ui.chromeVisible, ui.barsRevealed)
    }
}

/**
 * Screen behaviour from (route, reader state, settings). Only the reader route changes anything; the library follows
 * the system. Fullscreen hides the system bars while the controls are hidden and shows them with the controls (the
 * page is inset by a stable amount, so neither reflows it), while the user's swipe has them in (C-104) and on an error screen. Keep-screen-on needs a book on
 * screen. An orientation lock follows the sensor within the locked orientation (either way up).
 */
fun readerWindowState(inReader: Boolean, r: ReaderWindowInputs, s: ReaderSettings): ReaderWindowState =
    if (!inReader) ReaderWindowState(false, false, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    else ReaderWindowState(
        barsHidden = s.fullscreen && !r.chromeVisible && !r.barsRevealed && !r.error,
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
 * C-103: the system bars come back over the fullscreen reader only when the user swipes them in from the top or bottom
 * edge (the window keeps the default behaviour, so the system's Back gesture stays on both edges). Told what the app asks
 * of the bars ([requested]) and each change of their visibility ([seen]), it says when the user did that: hidden to shown
 * while the app wants them hidden, with the window focused (another window on top may show the bars too). A show the app
 * asked for and the system hasn't reported yet is the app's, even if the app asked to hide them again meanwhile. The
 * activity then tells the reader, which keeps only the bars in for a while (C-104).
 */
class BarsRevealWatch {
    private var last: Boolean? = null
    private var hiddenWanted = false
    private var ownShowPending = false
    fun requested(hidden: Boolean) {
        if (!hidden && last != true) ownShowPending = true
        hiddenWanted = hidden
    }
    fun seen(visible: Boolean, focused: Boolean): Boolean {
        val was = last
        last = visible
        if (!visible) return false
        val own = ownShowPending
        ownShowPending = false
        return was == false && hiddenWanted && focused && !own
    }
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

/**
 * C-103: calls [onChange] with the system bars' visibility (the status bar stands for them: the reader shows and hides
 * them together), at the start and on every change. Its own composable, so a change recomposes only this and not the
 * activity's root.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
fun BarsVisibility(onChange: (Boolean) -> Unit) {
    val visible = WindowInsets.areStatusBarsVisible
    val call by androidx.compose.runtime.rememberUpdatedState(onChange)
    androidx.compose.runtime.LaunchedEffect(visible) { call(visible) }
}
