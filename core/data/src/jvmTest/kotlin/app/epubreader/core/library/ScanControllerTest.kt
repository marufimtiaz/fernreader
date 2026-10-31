package app.epubreader.core.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun resume_within_2s_of_complete_is_skipped() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { true }, nowMs = { now })
        c.onAppStart(); runCurrent()
        now += 1_500; c.onLibraryResumed(); runCurrent()
        assertEquals(1, runs)
        now += 1_000; c.onLibraryResumed(); runCurrent()
        assertEquals(2, runs)
    }

    @Test fun no_access_no_scan() = runTest {
        val c = ScanController(::fakeScan, backgroundScope, access = { false }, nowMs = { now })
        c.onAppStart(); c.rescan(); c.onLibraryResumed(); runCurrent()
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
