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

    @Test fun defaults_in_the_reader_hide_bars_let_the_screen_time_out_follow_system() {
        val w = state()
        assertTrue(w.barsHidden); assertFalse(w.keepScreenOn) // C-106: keep-screen-on is off by default
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, w.orientation)
    }

    @Test fun showing_the_controls_shows_the_bars() {
        assertFalse(state(chrome = true).barsHidden)
        assertTrue(state(chrome = false).barsHidden)
    }

    /** C-104: bars the user swiped in stay in without the controls, until the reader lets them go. */
    @Test fun swiped_in_bars_stay_shown_without_the_controls() {
        assertFalse(state(ui = reading.copy(barsRevealed = true)).barsHidden)
        assertFalse(ReaderWindowInputs.of(reading) == ReaderWindowInputs.of(reading.copy(barsRevealed = true)))
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
        assertTrue(state(chrome = true, s = defaults.copy(keepScreenOn = true)).keepScreenOn)
    }

    /** C-108 (d): the reader asks for the reading rate while a book is shown; elsewhere the app does not ask. */
    @Test fun a_shown_book_asks_for_the_reading_rate_and_nothing_else_does() {
        assertEquals(RefreshRate.READING, state().refresh)
        assertEquals(RefreshRate.READING, state(chrome = true).refresh)
        assertEquals(RefreshRate.ANY, state(inReader = false).refresh)
        assertEquals(RefreshRate.ANY, state(ui = ReaderUiState()).refresh) // no book shown yet
        assertEquals(RefreshRate.LOWEST, readerWindowState(true, ReaderWindowInputs.of(reading), defaults, lowestRefresh = true).refresh)
        assertEquals(RefreshRate.ANY, readerWindowState(false, ReaderWindowInputs.of(reading), defaults, lowestRefresh = true).refresh)
    }

    /** C-108 (d): 60 Hz, or the panel's lowest rate if that is higher; C-107's lowest is the panel's lowest. 0 = no request. */
    @Test fun the_requested_rate_comes_from_the_panel_s_rates() {
        assertEquals(0f, preferredRate(RefreshRate.ANY, listOf(60f, 120f)))
        assertEquals(60f, preferredRate(RefreshRate.READING, listOf(120f, 60f, 90f)))
        assertEquals(60.000004f, preferredRate(RefreshRate.READING, listOf(120.00001f, 60.000004f)))
        assertEquals(90f, preferredRate(RefreshRate.READING, listOf(90f, 120f))) // lowest is higher than 60
        assertEquals(60f, preferredRate(RefreshRate.READING, listOf(30f, 60f, 120f))) // 60, not the lowest
        assertEquals(30f, preferredRate(RefreshRate.LOWEST, listOf(30f, 60f, 120f)))
        assertEquals(60f, preferredRate(RefreshRate.READING, listOf(60f)))
        assertEquals(90f, preferredRate(RefreshRate.READING, listOf(48f, 90f, 120f))) // no 60: the next rate above it
        assertEquals(0f, preferredRate(RefreshRate.READING, emptyList()))
    }

    /**
     * M7 (review): with auto-rotate on, a lock follows the sensor within the locked orientation, so the page is never upside
     * down. C-108 (g): the USER variants, so with auto-rotate off the system does not listen to the accelerometer for it.
     */
    @Test fun orientation_lock_maps_to_the_user_variant_of_the_requested_orientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, state(s = defaults.copy(orientation = OrientationLock.PORTRAIT)).orientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, state(s = defaults.copy(orientation = OrientationLock.LANDSCAPE)).orientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, state(s = defaults.copy(orientation = OrientationLock.SYSTEM)).orientation)
    }

    /** M6 (review): an error screen (open failure, R-008) lets the screen time out and shows the bars. */
    @Test fun error_screens_show_the_bars_and_let_the_screen_time_out() {
        for (err in listOf<ReaderError>(ReaderError.Open(OpenFailure.NO_ACCESS), ReaderError.FileChanged, ReaderError.Engine("x", "y"))) {
            val w = state(ui = reading.copy(error = err), s = defaults.copy(keepScreenOn = true))
            assertFalse("$err: keep-screen-on", w.keepScreenOn)
            assertFalse("$err: bars", w.barsHidden)
        }
    }

    @Test fun keep_screen_on_waits_until_the_book_is_shown() {
        assertFalse(state(ui = reading.copy(loading = true), s = defaults.copy(keepScreenOn = true)).keepScreenOn)
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

    /** P-015: the reader's window waits for the reader's first frame; leaving the reader restores the library's at once. */
    @Test fun the_reader_window_waits_for_the_readers_first_frame() {
        assertFalse(readerWindowActive(inReader = true, readerFramed = false))
        assertTrue(readerWindowActive(inReader = true, readerFramed = true))
        assertFalse(readerWindowActive(inReader = false, readerFramed = true))
        assertFalse(readerWindowActive(inReader = false, readerFramed = false))
    }

    /** C-103: only the user's swipe (hidden to shown while the app wants them hidden, focused) counts as a reveal. */
    @Test fun a_reveal_is_the_bars_coming_back_while_the_app_wants_them_hidden() {
        val w = BarsRevealWatch()
        assertFalse("the first look is no change", w.seen(visible = true, focused = true))
        w.requested(hidden = true)
        assertFalse("the app hid them", w.seen(visible = false, focused = true))
        assertTrue("the user's swipe", w.seen(visible = true, focused = true))
        assertFalse("still shown: not again", w.seen(visible = true, focused = true))
        w.seen(visible = false, focused = true)
        w.requested(hidden = false)
        assertFalse("the app showed them (with the controls)", w.seen(visible = true, focused = true))
        w.requested(hidden = true)
        w.seen(visible = false, focused = true)
        assertFalse("another window on top", w.seen(visible = true, focused = false))
    }

    /**
     * C-108 (h): the system shows the bars while the app is away (Home, recents) and the reader's first frame back still
     * sees them shown: that is no swipe (it showed the bars for 3 s and redrew ~145 frames on every return).
     */
    @Test fun bars_seen_shown_on_a_return_are_no_reveal() {
        val w = BarsRevealWatch()
        w.requested(hidden = true)
        w.seen(visible = false, focused = true)
        w.away() // the activity stopped
        w.requested(hidden = true) // the resume's reapply
        assertFalse("shown on the return", w.seen(visible = true, focused = true))
        w.seen(visible = false, focused = true) // the system applies the reader's hidden bars
        assertTrue("a later swipe is the user's", w.seen(visible = true, focused = true))
    }

    /** C-103: the app shows the bars and hides them again before the system reports them shown: still the app's show. */
    @Test fun a_quick_show_and_hide_by_the_app_is_no_reveal() {
        val w = BarsRevealWatch()
        w.requested(hidden = true)
        w.seen(visible = false, focused = true)
        w.requested(hidden = false)
        w.requested(hidden = true)
        assertFalse(w.seen(visible = true, focused = true))
        w.seen(visible = false, focused = true)
        assertTrue("a later swipe is the user's", w.seen(visible = true, focused = true))
    }
}
