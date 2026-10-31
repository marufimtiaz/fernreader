package app.epubreader.reader

import android.content.pm.ActivityInfo
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-035: what the window should look like, from (route, book open, controls shown, settings). */
class ReaderWindowStateTest {
    private val defaults = ReaderSettings()
    private fun state(inReader: Boolean = true, bookOpen: Boolean = true, chrome: Boolean = false, s: ReaderSettings = defaults) =
        readerWindowState(inReader, bookOpen, chrome, s)

    @Test fun defaults_in_the_reader_hide_bars_keep_screen_on_follow_system() {
        val w = state()
        assertTrue(w.barsHidden); assertTrue(w.keepScreenOn)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, w.orientation)
    }

    @Test fun showing_the_controls_shows_the_bars() {
        assertFalse(state(chrome = true).barsHidden)
        assertTrue(state(chrome = false).barsHidden)
    }

    @Test fun fullscreen_off_never_hides_bars() {
        assertFalse(state(s = defaults.copy(fullscreen = false)).barsHidden)
    }

    @Test fun outside_the_reader_nothing_is_applied() {
        val s = defaults.copy(orientation = OrientationLock.PORTRAIT)
        val w = state(inReader = false, s = s)
        assertFalse(w.barsHidden); assertFalse(w.keepScreenOn)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, w.orientation)
    }

    @Test fun keep_screen_on_needs_an_open_book_and_the_setting() {
        assertFalse(state(bookOpen = false).keepScreenOn)
        assertFalse(state(s = defaults.copy(keepScreenOn = false)).keepScreenOn)
        assertTrue(state(chrome = true).keepScreenOn)
    }

    @Test fun orientation_lock_maps_to_the_requested_orientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, state(s = defaults.copy(orientation = OrientationLock.PORTRAIT)).orientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, state(s = defaults.copy(orientation = OrientationLock.LANDSCAPE)).orientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, state(s = defaults.copy(orientation = OrientationLock.SYSTEM)).orientation)
    }
}
