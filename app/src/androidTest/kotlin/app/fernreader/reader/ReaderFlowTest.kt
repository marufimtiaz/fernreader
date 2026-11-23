package app.fernreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.SourceRef
import app.fernreader.reader.bridge.FromWeb
import app.fernreader.reader.bridge.ToWeb
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

    /**
     * Reader events in arrival order, with the helpers the tests share.
     *
     * Where the reader is is never inferred from timing: foliate re-reports (and refines) the opening page after its
     * first paint, later on a loaded host, and such a report must not be taken for the answer to a turn. Two barriers
     * order things instead: [settle] queues a jump with an id to the current location behind everything sent before (the
     * web side runs turns and jumps one at a time, after the open has settled) and waits for its `seeked`; [flush] waits for a `pong`,
     * which the web side answers at once, after every event it sent before.
     */
    private class Events(private val ch: Channel<FromWeb>, private val send: (ToWeb) -> Unit) {
        var at: FromWeb.Relocate? = null // latest location seen
        private var ids = 1_000 // the controller's own jump ids are small; it ignores a `seeked` that is not its own
        private fun see(e: FromWeb) { if (e is FromWeb.Relocate) at = e }
        fun drain() { while (true) see(ch.tryReceive().getOrNull() ?: return) }
        suspend fun relocate(ms: Long, accept: (FromWeb.Relocate) -> Boolean = { true }): FromWeb.Relocate = withTimeout(ms) {
            while (true) { val e = ch.receive(); see(e); if (e is FromWeb.Relocate && accept(e)) return@withTimeout e }
            error("unreachable")
        }
        private suspend fun until(ms: Long, done: (FromWeb) -> Boolean) = withTimeout(ms) { while (true) { val e = ch.receive(); see(e); if (done(e)) break } }
        /** The next `painted` in arrival order, i.e. after everything already received. */
        suspend fun painted() = until(10_000) { it is FromWeb.Painted }
        /**
         * Call only when nothing else is pending (the jump goes to the location seen last). Everything sent before has
         * been applied once the jump to the current location has landed. Returns that location (the landing relocate
         * comes right before `seeked`), after its paint.
         */
        suspend fun settle(): FromWeb.Relocate {
            val id = ++ids
            send(ToWeb.GoTo(target = at!!.cfi, id = id))
            until(10_000) { it is FromWeb.Seeked && it.id == id }
            return at!!.also { painted() }
        }
        /** Every event the web side sent before now has been received (after a close: the last location of the book). */
        suspend fun flush(): FromWeb.Relocate? {
            val id = ++ids
            send(ToWeb.Ping(id = id))
            until(5_000) { it is FromWeb.Pong && it.id == id }
            return at
        }
        /** Open → first page painted and the open settled; returns the opening location. */
        suspend fun opened(): FromWeb.Relocate { relocate(10_000); painted(); return settle() }
        /**
         * One turn from a settled location: the first relocate at another page (a new CFI; foliate may still refine the
         * fraction under the same one), then settled there, so the next command starts from where this one ended.
         */
        suspend fun turn(cmd: ToWeb): FromWeb.Relocate {
            val from = at!!; send(cmd)
            relocate(5_000) { it.cfi != from.cfi }
            return settle()
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
            val ev = Events(ch) { m -> sc.onActivity { it.reader.send(m) } }

            sc.onActivity { it.openBook(ref) }
            val first = ev.opened()
            var last = first
            repeat(5) { last = ev.turn(ToWeb.Next()) }
            assertNotEquals(first.cfi, last.cfi)

            sc.onActivity { it.goBack() }
            last = ev.flush()!! // the last location the reader reported before the close
            val graph = (a.application as EpubReaderApp).graph
            val bookId = graph.library.knownBookId(ref)!!
            withTimeout(3_000) { while (graph.library.position(bookId)?.cfi != last.cfi) delay(20) } // R-009

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
            val ev = Events(ch) { m -> sc.onActivity { it.reader.send(m) } }

            sc.onActivity { it.openBook(SourceRef.File(one.path)) }
            val start = ev.opened()
            var third = start
            repeat(3) { third = ev.turn(ToWeb.Next()) }
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
