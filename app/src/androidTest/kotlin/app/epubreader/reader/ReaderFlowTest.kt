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
import kotlinx.coroutines.flow.filterIsInstance
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

    @Test fun open_turn_back_reopen_resumes_same_cfi(): Unit = runBlocking {
        val f = TestBooks.write(ctx, "flow.epub", TestBooks.epub(id = "urn:uuid:flow-${System.nanoTime()}", chapters = 3, paragraphs = 80))
        val ref = SourceRef.File(f.path)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val relocates = Channel<FromWeb.Relocate>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { relocates.send(it) } }

            sc.onActivity { it.openBook(ref) }
            val first = withTimeout(10_000) { relocates.receive() }
            var last = first
            repeat(5) { sc.onActivity { it.reader.send(ToWeb.Next()) }; last = withTimeout(5_000) { relocates.receive() } }
            assertNotEquals(first.cfi, last.cfi)

            sc.onActivity { it.goBack() }
            val graph = (a.application as EpubReaderApp).graph
            val bookId = graph.library.knownBookId(ref)!!
            withTimeout(3_000) { while (graph.library.position(bookId)?.cfi != last.cfi) delay(20) } // R-009

            sc.onActivity { it.openBook(ref) } // warm reopen (same session id)
            assertEquals(last.cfi, withTimeout(10_000) { relocates.receive() }.cfi)
            job.cancel()
        }
    }
}
