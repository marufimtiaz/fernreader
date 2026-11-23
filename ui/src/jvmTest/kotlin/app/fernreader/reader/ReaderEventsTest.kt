package app.fernreader.reader

import app.fernreader.reader.bridge.FromWeb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderEventsTest {
    // A collector suspended on a database write must not lose events from a burst (e.g. the user's last relocate).
    @Test fun burst_while_collector_is_slow_drops_nothing() = runTest {
        val ev = ReaderEvents()
        val got = mutableListOf<FromWeb>()
        val gate = CompletableDeferred<Unit>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { ev.flow.collect { gate.await(); got += it } }
        repeat(1000) { i -> assertTrue(ev.emit(FromWeb.Relocate(sessionId = "s", cfi = "c$i", fraction = 0.0, sectionIndex = 0, tocHref = null))) }
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1000, got.size); assertEquals("c999", (got.last() as FromWeb.Relocate).cfi)
        job.cancel()
    }
}
