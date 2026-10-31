package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** F-030 in place, F-037 resume, R-009 position written. Uses a generated book: no corpus, no permission. */
@RunWith(AndroidJUnit4::class)
class ReaderFlowTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun open_turn_back_reopen_resumes_same_cfi(): Unit = runBlocking {
        val f = TestBooks.write(ctx, "flow.epub", TestBooks.epub(id = "urn:uuid:flow-${System.nanoTime()}", chapters = 3, paragraphs = 80))
        val ref = SourceRef.File(f.path)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val events = Channel<FromWeb>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { events.send(it) } }
            suspend fun relocate(ms: Long, accept: (FromWeb.Relocate) -> Boolean = { true }): FromWeb.Relocate =
                withTimeout(ms) { while (true) { val e = events.receive(); if (e is FromWeb.Relocate && accept(e)) return@withTimeout e }; error("unreachable") }
            suspend fun painted() = withTimeout(10_000) { while (events.receive() !is FromWeb.Painted) Unit }

            sc.onActivity { it.openBook(ref) }
            var first = relocate(10_000)
            // One turn at a time, like a reader: foliate repeats and refines the opening location while the first section
            // settles and drops turns sent meanwhile or during a turn animation, and may report one CFI for two pages
            // (so a turn is any move). Turn once no relocate came for 500 ms, then after each turn's paint.
            while (true) first = withTimeoutOrNull(500) { relocate(Long.MAX_VALUE) } ?: break
            var last = first
            repeat(5) {
                sc.onActivity { it.reader.send(ToWeb.Next()) }
                val prev = last
                last = relocate(5_000) { it.cfi != prev.cfi || it.fraction != prev.fraction }
                painted()
            }
            assertNotEquals(first.cfi, last.cfi)

            sc.onActivity { it.goBack() }
            val graph = (a.application as EpubReaderApp).graph
            val bookId = graph.library.knownBookId(ref)!!
            withTimeout(3_000) { while (graph.library.position(bookId)?.cfi != last.cfi) delay(20) } // R-009
            while (events.tryReceive().isSuccess) Unit // events of the first visit

            sc.onActivity { it.openBook(ref) } // warm reopen (same session id)
            assertEquals(last.cfi, relocate(10_000).cfi)
            job.cancel()
        }
    }
}
