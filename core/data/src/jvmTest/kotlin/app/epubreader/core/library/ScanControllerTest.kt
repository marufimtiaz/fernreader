package app.epubreader.core.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerTest {
    private var runs = 0
    private var gate: CompletableDeferred<Unit>? = null
    private val done = ScanEvent.Complete(0, 0, 0, 0, 1)
    private suspend fun fakeScan(s: ScanScope, on: (ScanEvent) -> Unit): ScanEvent.Complete { runs++; gate?.await(); on(done); return done }

    @Test fun request_while_running_runs_once_more() = runTest {
        gate = CompletableDeferred()
        val c = ScanController(::fakeScan, backgroundScope, access = { true })
        c.onAppStart(); runCurrent()
        assertTrue(c.state.value.running)
        c.rescan(); c.rescan(); c.rescan() // collapse into one follow-up
        gate!!.complete(Unit); runCurrent()
        assertEquals(2, runs)
        assertEquals(false, c.state.value.running)
        assertEquals(done, c.state.value.lastComplete)
    }

    /**
     * C-075: every view that comes into view scans, even right after a scan (a book copied meanwhile, e.g. over USB
     * while the app is in front, must show); only the library's first composition waits for the app start scan.
     */
    @Test fun view_shown_scans_even_right_after_a_scan() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { true })
        c.onViewShown(); runCurrent()
        assertEquals(0, runs) // the library's first composition: the app start scan comes after the first frame
        c.onAppStart(); runCurrent()
        c.onViewShown(); runCurrent()
        assertEquals(2, runs)
        c.onViewShown(); runCurrent()
        assertEquals(3, runs)
    }

    /**
     * C-075: every return to the foreground onto the library or one of its views scans, even right after a scan; one that
     * arrives mid-scan runs after it.
     */
    @Test fun foreground_on_the_library_always_scans_and_mid_scan_queues_one_more() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { true })
        c.onAppStart(); runCurrent()
        c.onForeground(libraryShown = true); runCurrent()
        assertEquals(2, runs)
        gate = CompletableDeferred()
        c.onForeground(libraryShown = true); runCurrent()
        assertTrue(c.state.value.running)
        c.onForeground(libraryShown = true); c.onViewShown()
        gate!!.complete(Unit); runCurrent()
        assertEquals(4, runs) // the walk may have passed the new folder already: one more scan, not none
    }

    /** C-108 (f): a return to the foreground onto the reader (or any screen but the library's) does not scan. */
    @Test fun foreground_elsewhere_does_not_scan() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { true })
        c.onAppStart(); runCurrent()
        c.onForeground(libraryShown = false); runCurrent()
        assertEquals(1, runs)
        c.onViewShown(); runCurrent() // back to the library from the reader: that scans
        assertEquals(2, runs)
    }

    /** P-002: resume and view scans start after [resumeDelayMs] (off the resumed screen's first frame); others at once. */
    @Test fun resume_and_view_scans_wait_a_moment_app_start_and_rescan_do_not() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { true }, resumeDelayMs = 300)
        c.onAppStart(); runCurrent()
        assertEquals(1, runs) // app start: at once
        c.onForeground(libraryShown = true); runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(299); runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, runs) // after the delay
        c.onViewShown(); runCurrent()
        assertEquals(2, runs)
        advanceTimeBy(301); runCurrent()
        assertEquals(3, runs)
        c.rescan(); runCurrent()
        assertEquals(4, runs) // manual: at once
    }

    /** C-075: an automatic scan shows no progress unless it finds something; a manual rescan always does. */
    @Test fun quiet_scans_show_progress_only_once_a_book_is_found() = runTest {
        val found = CompletableDeferred<Unit>()
        val c = ScanController({ _, on -> runs++; gate?.await(); on(ScanEvent.BookAdded("b", "/n.epub")); found.await(); on(done); done },
            backgroundScope, access = { true })
        gate = CompletableDeferred()
        c.onForeground(libraryShown = true); runCurrent()
        assertTrue(c.state.value.running); assertFalse(c.state.value.showsProgress)
        gate!!.complete(Unit); runCurrent()
        assertTrue(c.state.value.showsProgress) // a book was found: books appear as they are found, with progress
        found.complete(Unit); runCurrent()
        assertFalse(c.state.value.showsProgress)
    }

    @Test fun manual_rescan_shows_progress_even_when_collapsed_with_a_quiet_one() = runTest {
        gate = CompletableDeferred()
        val c = ScanController(::fakeScan, backgroundScope, access = { true })
        c.onAppStart(); runCurrent()
        assertFalse(c.state.value.showsProgress)
        c.onForeground(libraryShown = true); c.rescan() // one follow-up, and it is the manual one
        gate!!.complete(Unit); gate = CompletableDeferred(); runCurrent()
        assertEquals(2, runs); assertTrue(c.state.value.showsProgress)
        gate!!.complete(Unit); runCurrent()
        assertFalse(c.state.value.running)
    }

    @Test fun no_access_no_scan() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { false })
        c.onAppStart(); c.rescan(); c.onForeground(libraryShown = true); c.onViewShown(); runCurrent()
        assertEquals(0, runs)
    }

    @Test fun a_failing_scan_is_reported_and_the_next_one_runs() = runTest {
        val probes = mutableListOf<String>()
        var fail = true
        val c = ScanController({ _, _ -> runs++; if (fail) error("disk gone"); done }, backgroundScope, access = { true },
            probe = { n, _ -> probes += n })
        c.onAppStart(); runCurrent()
        fail = false; c.rescan(); runCurrent()
        assertEquals(2, runs); assertTrue("scan_error" in probes); assertEquals(done, c.state.value.lastComplete)
    }
}
