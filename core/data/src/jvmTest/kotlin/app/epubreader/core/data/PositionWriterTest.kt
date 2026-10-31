package app.epubreader.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PositionWriterTest {
    /** R-009: a burst of page changes ends with the last one stored; an older write never replaces a newer row. */
    @Test fun burst_ends_on_last_and_never_older() = runBlocking {
        val db = testDb()
        val clock = HybridClock { 1_000L } // frozen wall clock: HybridClock must still order the writes
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val w = PositionWriter(db, clock, scope)
        repeat(1000) { w.submit(PositionWriter.Update("b1", "cfi$it", it / 1000.0, it / 100)) }
        withTimeout(5_000) { while (db.positionDao().get("b1")?.cfi != "cfi999") delay(10) }
        assertFalse(db.positionDao().put("b1", "stale", 0.0, 0, at = 1))
        assertEquals("cfi999", db.positionDao().get("b1")!!.cfi)
        scope.cancel()
    }

    @Test fun write_now_is_visible_immediately() = runBlocking {
        val db = testDb()
        val w = PositionWriter(db, HybridClock(System::currentTimeMillis), CoroutineScope(Dispatchers.IO))
        w.writeNow(PositionWriter.Update("b2", "epubcfi(/6/4!/4/2)", 0.1, 1))
        assertEquals("epubcfi(/6/4!/4/2)", db.positionDao().get("b2")!!.cfi)
    }
}
