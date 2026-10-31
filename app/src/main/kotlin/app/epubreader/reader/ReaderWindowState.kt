package app.epubreader.reader

import android.content.pm.ActivityInfo
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.ReaderSettings

/** What the activity's window should look like (F-035). */
data class ReaderWindowState(val barsHidden: Boolean, val keepScreenOn: Boolean, val orientation: Int)

/**
 * Screen behaviour from (route, book open, controls shown, settings). Only the reader route changes anything; the
 * library follows the system. Fullscreen hides the system bars while the controls are hidden and shows them with the
 * controls (the page is inset by a stable amount, so neither reflows it). Keep-screen-on needs an open book.
 */
fun readerWindowState(inReader: Boolean, bookOpen: Boolean, chromeVisible: Boolean, s: ReaderSettings): ReaderWindowState =
    if (!inReader) ReaderWindowState(false, false, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    else ReaderWindowState(
        barsHidden = s.fullscreen && !chromeVisible,
        keepScreenOn = s.keepScreenOn && bookOpen,
        orientation = when (s.orientation) {
            OrientationLock.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        },
    )
