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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** F-030 in place, F-037 resume, R-009 position written. Uses a generated book: no corpus, no permission. */
@RunWith(AndroidJUnit4::class)
class ReaderFlowTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** Reader events in arrival order, with the helpers the tests share. */
    private class Events(private val ch: Channel<FromWeb>) {
        var at: FromWeb.Relocate? = null // latest location seen
        private fun see(e: FromWeb) { if (e is FromWeb.Relocate) at = e }
        fun drain() { while (true) see(ch.tryReceive().getOrNull() ?: return) }
        suspend fun relocate(ms: Long, accept: (FromWeb.Relocate) -> Boolean = { true }): FromWeb.Relocate = withTimeout(ms) {
            while (true) { val e = ch.receive(); see(e); if (e is FromWeb.Relocate && accept(e)) return@withTimeout e }
            error("unreachable")
        }
        /** The next `painted` in arrival order, i.e. after everything already received. */
        suspend fun painted() = withTimeout(10_000) { while (true) { val e = ch.receive(); see(e); if (e is FromWeb.Painted) break } }
        /** Open → first page painted; [at] is then the settled opening location (foliate may repeat it meanwhile). */
        suspend fun opened(): FromWeb.Relocate { relocate(10_000); painted(); drain(); return at!! }
        /** One turn: anything received before the command is consumed first, so the move and its paint come after it. */
        suspend fun turn(send: () -> Unit): FromWeb.Relocate {
            drain(); val from = at!!; send()
            return relocate(5_000) { it.cfi != from.cfi || it.fraction != from.fraction }.also { painted() }
        }
    }

    @Test fun open_turn_back_reopen_resumes_same_cfi(): Unit = runBlocking {
        val f = TestBooks.write(ctx, "flow.epub", TestBooks.epub(id = "urn:uuid:flow-${System.nanoTime()}", chapters = 3, paragraphs = 80))
        val ref = SourceRef.File(f.path)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val ch = Channel<FromWeb>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { ch.send(it) } }
            val ev = Events(ch)

            sc.onActivity { it.openBook(ref) }
            val first = ev.opened()
            var last = first
            repeat(5) { last = ev.turn { sc.onActivity { it.reader.send(ToWeb.Next()) } } }
            assertNotEquals(first.cfi, last.cfi)

            sc.onActivity { it.goBack() }
            val graph = (a.application as EpubReaderApp).graph
            val bookId = graph.library.knownBookId(ref)!!
            withTimeout(3_000) { while (graph.library.position(bookId)?.cfi != last.cfi) delay(20) } // R-009
            ev.drain() // events of the first visit

            sc.onActivity { it.openBook(ref) } // warm reopen (same session id)
            assertEquals(last.cfi, ev.relocate(10_000).cfi)
            job.cancel()
        }
    }

    /** F-030: turns sent right after opening, before the first page is laid out, are kept and applied in order. */
    @Test fun quick_turns_right_after_open_are_not_lost(): Unit = runBlocking {
        val n = System.nanoTime()
        // Same content, two books: the first gives the location three single turns reach.
        val one = TestBooks.write(ctx, "turns-a.epub", TestBooks.epub(id = "urn:uuid:turns-a-$n", chapters = 3, paragraphs = 80))
        val two = TestBooks.write(ctx, "turns-b.epub", TestBooks.epub(id = "urn:uuid:turns-b-$n", chapters = 3, paragraphs = 80))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val ch = Channel<FromWeb>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { ch.send(it) } }
            val ev = Events(ch)

            sc.onActivity { it.openBook(SourceRef.File(one.path)) }
            val start = ev.opened()
            var third = start
            repeat(3) { third = ev.turn { sc.onActivity { it.reader.send(ToWeb.Next()) } } }
            assertNotEquals(start.cfi, third.cfi)
            sc.onActivity { it.goBack() }
            ev.drain()

            sc.onActivity { it.openBook(SourceRef.File(two.path)) }
            // `opened` comes when the package is parsed, before the first page is laid out or shown: turn at once.
            withTimeout(10_000) { while (ch.receive() !is FromWeb.Opened) Unit }
            sc.onActivity { repeat(3) { _ -> it.reader.send(ToWeb.Next()) } }
            ev.relocate(15_000) { it.cfi == third.cfi && it.fraction == third.fraction }
            job.cancel()
        }
    }
}
