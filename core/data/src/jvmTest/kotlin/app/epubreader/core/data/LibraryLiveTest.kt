package app.epubreader.core.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * C-108 (a): the live library updates from data changes only. Collected on a dispatcher that counts its dispatches (each
 * one is a thread wakeup in the app), a library with nothing changing must not wake its collector at all.
 */
class LibraryLiveTest {
    private val pool = Executors.newSingleThreadExecutor()
    private val dispatches = AtomicInteger()
    private val counting = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) { dispatches.incrementAndGet(); pool.execute(block) }
    }

    @AfterTest fun stop() { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!ok()) { check(System.nanoTime() < end) { "timed out: $what" }; Thread.sleep(10) }
    }

    @Test fun an_unchanging_library_wakes_nothing() = runBlocking {
        val repo = LibraryRepository(testDb(), HybridClock { 1L })
        val found = MutableStateFlow(0)
        val seen = CopyOnWriteArrayList<Int>()
        val job: Job = CoroutineScope(counting).launch { repo.libraryLive(found, everyMs = 50).collect { seen += it.size } }
        waitFor("the first list") { seen.isNotEmpty() }
        found.value = 1 // a scan found a book: one read, then quiet again
        waitFor("the read after a find") { seen.size >= 2 }
        Thread.sleep(300) // past any throttle window
        val before = dispatches.get()
        Thread.sleep(1_000)
        assertEquals(before, dispatches.get(), "dispatches while nothing changed")
        job.cancelAndJoin()
    }

    @Test fun finds_are_read_at_once_then_at_most_once_per_period_and_the_last_one_always() = runBlocking {
        val repo = LibraryRepository(testDb(), HybridClock { 1L })
        val found = MutableStateFlow(0)
        val at = CopyOnWriteArrayList<Long>()
        val job: Job = CoroutineScope(counting).launch { repo.libraryLive(found, everyMs = 200).collect { at += System.nanoTime() } }
        waitFor("the first list") { at.isNotEmpty() }
        val t0 = System.nanoTime()
        found.value = 1
        waitFor("the first find read") { at.size >= 2 }
        assertTrue((at[1] - t0) / 1_000_000 < 150, "the first find is read at once")
        for (n in 2..10) { found.value = n; Thread.sleep(10) } // a burst within one period
        waitFor("the burst's last read") { at.size >= 3 }
        Thread.sleep(500)
        assertEquals(3, at.size, "a burst within one period is one more read")
        assertTrue((at[2] - at[1]) / 1_000_000 >= 190, "at most one read per period")
        job.cancelAndJoin()
    }
}
