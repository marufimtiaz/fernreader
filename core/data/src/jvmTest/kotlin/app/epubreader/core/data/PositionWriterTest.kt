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
        val w = PositionWriter.create(db, clock, scope)
        repeat(1000) { w.submit(PositionWriter.Update("b1", "cfi$it", it / 1000.0, it / 100)) }
        withTimeout(5_000) { while (db.positionDao().get("b1")?.cfi != "cfi999") delay(10) }
        assertFalse(db.positionDao().put("b1", "stale", 0.0, 0, at = 1))
        assertEquals("cfi999", db.positionDao().get("b1")!!.cfi)
        scope.cancel()
    }

    @Test fun failed_write_does_not_stop_later_positions() = runBlocking {
        val db = testDb()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val w = PositionWriter.create(db, HybridClock(System::currentTimeMillis), scope)
        var first = true
        w.beforeWrite = { if (first) { first = false; error("disk full") } }
        w.submit(PositionWriter.Update("b3", "cfi-a", 0.1, 0))
        withTimeout(5_000) { while (first) delay(5) }
        w.submit(PositionWriter.Update("b3", "cfi-b", 0.2, 0))
        withTimeout(5_000) { while (db.positionDao().get("b3")?.cfi != "cfi-b") delay(10) }
        scope.cancel()
    }

    @Test fun stored_timestamp_ahead_of_wall_clock_does_not_block_writes() = runBlocking {
        val db = testDb()
        db.positionDao().put("b4", "old", 0.0, 0, at = 9_000_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val w = PositionWriter.create(db, HybridClock { 1_000L }, scope)
        w.submit(PositionWriter.Update("b4", "new", 0.5, 1))
        withTimeout(5_000) { while (db.positionDao().get("b4")?.cfi != "new") delay(10) }
        w.writeNow(PositionWriter.Update("b4", "newer", 0.6, 1))
        assertEquals("newer", db.positionDao().get("b4")!!.cfi)
        scope.cancel()
    }

    @Test fun write_now_is_visible_immediately() = runBlocking {
        val db = testDb()
        val scope = CoroutineScope(Dispatchers.IO)
        val w = PositionWriter.create(db, HybridClock(System::currentTimeMillis), scope)
        w.writeNow(PositionWriter.Update("b2", "epubcfi(/6/4!/4/2)", 0.1, 1))
        assertEquals("epubcfi(/6/4!/4/2)", db.positionDao().get("b2")!!.cfi)
        scope.cancel()
    }

    @Test fun skewed_clock_submit_then_write_now_keeps_the_newer() = runBlocking {
        val db = testDb()
        db.positionDao().put("b5", "old", 0.0, 0, at = 9_000_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val w = PositionWriter.create(db, HybridClock { 1_000L }, scope)
        w.submit(PositionWriter.Update("b5", "A", 0.1, 0))
        w.writeNow(PositionWriter.Update("b5", "B", 0.2, 0))
        delay(300) // let the queued, older A drain
        assertEquals("B", db.positionDao().get("b5")!!.cfi)
        scope.cancel()
    }

    @Test fun burst_right_after_construction_ends_on_last() = runBlocking {
        val db = testDb()
        db.positionDao().put("b6", "old", 0.0, 0, at = 9_000_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val w = PositionWriter.create(db, HybridClock { 1_000L }, scope)
        repeat(500) { w.submit(PositionWriter.Update("b6", "c$it", 0.0, 0)) }
        withTimeout(5_000) { while (db.positionDao().get("b6")?.cfi != "c499") delay(10) }
        delay(100)
        assertEquals("c499", db.positionDao().get("b6")!!.cfi)
        scope.cancel()
    }
}
