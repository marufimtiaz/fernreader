package app.epubreader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.ReaderEngine
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.ui.Route
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-build smoke test (tools/smoke.sh). Functional only, no timing assertions (C-015).
 * M1: the app starts and the pre-warmed reader host page becomes ready. M2 extends it (open, turn, back).
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test fun app_starts_and_reader_host_is_ready(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var engine: ReaderEngine
            sc.onActivity { engine = it.reader }
            withTimeout(15_000) { engine.isReady.first { it } }
        }
    }

    @Test fun opens_a_book_turns_a_page_and_goes_back(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = TestBooks.write(ctx, "smoke.epub")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val events = Channel<FromWeb>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.collect { events.send(it) } }
            var at: FromWeb.Relocate? = null
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            // Up to the first paint; the latest relocate is then the opening location (foliate may repeat it meanwhile).
            withTimeout(10_000) { while (true) { val e = events.receive(); if (e is FromWeb.Relocate) at = e; if (e is FromWeb.Painted && at != null) break } }
            while (true) { val e = events.tryReceive().getOrNull() ?: break; if (e is FromWeb.Relocate) at = e }
            val from = at!!
            sc.onActivity { it.reader.send(ToWeb.Next()) }
            // A turn: a relocate after the command at a new location (CFI) that lies ahead (later section, or further in the same
            // one). foliate can still refine the opening page's fraction under the same CFI; that is not a turn.
            fun advances(r: FromWeb.Relocate) = r.cfi != from.cfi &&
                (r.sectionIndex > from.sectionIndex || (r.sectionIndex == from.sectionIndex && r.fraction > from.fraction))
            val moved = withTimeout(5_000) {
                while (true) { val e = events.receive(); if (e is FromWeb.Relocate && advances(e)) return@withTimeout e }
                error("unreachable")
            }
            assertNotEquals(from.cfi, moved.cfi)
            job.cancel()
            sc.onActivity { assertTrue(it.goBack()); assertNotEquals(Route.Reader, it.nav.top) }
        }
    }
}
