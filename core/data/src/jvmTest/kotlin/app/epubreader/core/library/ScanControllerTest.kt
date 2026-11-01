package app.epubreader.core.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private var now = 0L
    private val done = ScanEvent.Complete(0, 0, 0, 0, 1)
    private suspend fun fakeScan(s: ScanScope, on: (ScanEvent) -> Unit): ScanEvent.Complete { runs++; gate?.await(); on(done); return done }

    @Test fun request_while_running_runs_once_more() = runTest {
        gate = CompletableDeferred()
        val c = ScanController(::fakeScan, backgroundScope, access = { true }, nowMs = { now })
        c.onAppStart(); runCurrent()
        assertTrue(c.state.value.running)
        c.rescan(); c.rescan(); c.rescan() // collapse into one follow-up
        gate!!.complete(Unit); runCurrent()
        assertEquals(2, runs)
        assertEquals(false, c.state.value.running)
        assertEquals(done, c.state.value.lastComplete)
    }

    @Test fun view_shown_within_2s_of_complete_is_skipped() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { true }, nowMs = { now })
        c.onViewShown(); runCurrent()
        assertEquals(0, runs) // the library's first composition: the app start scan comes after the first frame
        c.onAppStart(); runCurrent()
        now += 1_500; c.onViewShown(); runCurrent()
        assertEquals(1, runs)
        now += 1_000; c.onViewShown(); runCurrent()
        assertEquals(2, runs)
    }

    /** C-075: every return to the foreground scans, even right after a scan; one that arrives mid-scan runs after it. */
    @Test fun foreground_always_scans_and_mid_scan_queues_one_more() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { true }, nowMs = { now })
        c.onAppStart(); runCurrent()
        now += 100; c.onForeground(); runCurrent()
        assertEquals(2, runs)
        gate = CompletableDeferred()
        c.onForeground(); runCurrent()
        assertTrue(c.state.value.running)
        c.onForeground(); c.onViewShown()
        gate!!.complete(Unit); runCurrent()
        assertEquals(4, runs) // the walk may have passed the new folder already: one more scan, not none
    }

    /** C-075: an automatic scan shows no progress unless it finds something; a manual rescan always does. */
    @Test fun quiet_scans_show_progress_only_once_a_book_is_found() = runTest {
        val found = CompletableDeferred<Unit>()
        val c = ScanController({ _, on -> runs++; gate?.await(); on(ScanEvent.BookAdded("b", "/n.epub")); found.await(); on(done); done },
            backgroundScope, access = { true }, nowMs = { now })
        gate = CompletableDeferred()
        c.onForeground(); runCurrent()
        assertTrue(c.state.value.running); assertFalse(c.state.value.showsProgress)
        gate!!.complete(Unit); runCurrent()
        assertTrue(c.state.value.showsProgress) // a book was found: books appear as they are found, with progress
        found.complete(Unit); runCurrent()
        assertFalse(c.state.value.showsProgress)
    }

    @Test fun manual_rescan_shows_progress_even_when_collapsed_with_a_quiet_one() = runTest {
        gate = CompletableDeferred()
        val c = ScanController(::fakeScan, backgroundScope, access = { true }, nowMs = { now })
        c.onAppStart(); runCurrent()
        assertFalse(c.state.value.showsProgress)
        c.onForeground(); c.rescan() // one follow-up, and it is the manual one
        gate!!.complete(Unit); gate = CompletableDeferred(); runCurrent()
        assertEquals(2, runs); assertTrue(c.state.value.showsProgress)
        gate!!.complete(Unit); runCurrent()
        assertFalse(c.state.value.running)
    }

    @Test fun no_access_no_scan() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { false }, nowMs = { now })
        c.onAppStart(); c.rescan(); c.onForeground(); c.onViewShown(); runCurrent()
        assertEquals(0, runs)
    }

    @Test fun a_failing_scan_is_reported_and_the_next_one_runs() = runTest {
        val probes = mutableListOf<String>()
        var fail = true
        val c = ScanController({ _, _ -> runs++; if (fail) error("disk gone"); done }, backgroundScope, access = { true },
            probe = { n, _ -> probes += n }, nowMs = { now })
        c.onAppStart(); runCurrent()
        fail = false; c.rescan(); runCurrent()
        assertEquals(2, runs); assertTrue("scan_error" in probes); assertEquals(done, c.state.value.lastComplete)
    }
}
