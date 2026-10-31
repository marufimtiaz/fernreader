package app.epubreader.reader

import android.content.pm.ActivityInfo
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-035: what the window should look like, from (route, reader state, settings). */
class ReaderWindowStateTest {
    private val defaults = ReaderSettings()
    private val reading = ReaderUiState(open = true)
    private fun state(inReader: Boolean = true, ui: ReaderUiState = reading, chrome: Boolean = false, s: ReaderSettings = defaults) =
        readerWindowState(inReader, ReaderWindowInputs.of(ui.copy(chromeVisible = chrome)), s)

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
        assertFalse(state(ui = ReaderUiState()).keepScreenOn)
        assertFalse(state(s = defaults.copy(keepScreenOn = false)).keepScreenOn)
        assertTrue(state(chrome = true).keepScreenOn)
    }

    /** M7 (review): a lock follows the sensor within the locked orientation, so the page is never upside down. */
    @Test fun orientation_lock_maps_to_the_sensor_variant_of_the_requested_orientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, state(s = defaults.copy(orientation = OrientationLock.PORTRAIT)).orientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, state(s = defaults.copy(orientation = OrientationLock.LANDSCAPE)).orientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, state(s = defaults.copy(orientation = OrientationLock.SYSTEM)).orientation)
    }

    /** M6 (review): an error screen (open failure, R-008) lets the screen time out and shows the bars. */
    @Test fun error_screens_show_the_bars_and_let_the_screen_time_out() {
        for (err in listOf<ReaderError>(ReaderError.Open(OpenFailure.NO_ACCESS), ReaderError.FileChanged, ReaderError.Engine("x", "y"))) {
            val w = state(ui = reading.copy(error = err))
            assertFalse("$err: keep-screen-on", w.keepScreenOn)
            assertFalse("$err: bars", w.barsHidden)
        }
    }

    @Test fun keep_screen_on_waits_until_the_book_is_shown() {
        assertFalse(state(ui = reading.copy(loading = true)).keepScreenOn)
    }

    /** I2 (review): a page turn (new location, chapter, marker) changes nothing the window depends on. */
    @Test fun window_inputs_do_not_change_on_a_page_turn() {
        val before = reading.copy(fraction = 0.1, currentTocHref = "c1.xhtml")
        val after = before.copy(fraction = 0.2, currentTocHref = "c2.xhtml", bookmarked = true, notice = Notice.BookmarkAdded)
        assertEquals(ReaderWindowInputs.of(before), ReaderWindowInputs.of(after))
        assertFalse(ReaderWindowInputs.of(before) == ReaderWindowInputs.of(before.copy(chromeVisible = true)))
    }

    /** I2 (review): the window is touched only when what it should look like changes (each apply costs a relayout). */
    @Test fun the_window_is_applied_only_on_a_change() {
        val applied = mutableListOf<WindowLook>()
        val gate = WindowApplier { applied += it }
        val look = WindowLook(brightness = -1f, lightBars = true, win = state())
        gate.update(look); gate.update(look.copy()); gate.update(look)
        assertEquals(1, applied.size)
        gate.update(look.copy(win = state(chrome = true)))
        assertEquals(2, applied.size)
        gate.reapply() // e.g. back from another app: the last look again
        assertEquals(listOf(look, look.copy(win = state(chrome = true)), look.copy(win = state(chrome = true))), applied)
        WindowApplier { applied += it }.reapply() // nothing applied yet: nothing to repeat
        assertEquals(3, applied.size)
    }
}
