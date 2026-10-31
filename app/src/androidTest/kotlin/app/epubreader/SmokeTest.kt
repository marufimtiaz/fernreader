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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterIsInstance
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
            val relocates = Channel<FromWeb.Relocate>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { relocates.send(it) } }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            var at = withTimeout(10_000) { relocates.receive() }
            // foliate repeats and refines the opening location while the first section settles, and drops a turn sent
            // meanwhile: turn once no relocate came for 500 ms, like a reader would.
            while (true) at = withTimeoutOrNull(500) { relocates.receive() } ?: break
            sc.onActivity { it.reader.send(ToWeb.Next()) }
            val moved = withTimeout(5_000) { var r = relocates.receive(); while (r.cfi == at.cfi && r.fraction == at.fraction) r = relocates.receive(); r }
            assertNotEquals(at.cfi to at.fraction, moved.cfi to moved.fraction)
            job.cancel()
            sc.onActivity { assertTrue(it.goBack()); assertEquals(Route.Home, it.nav.top) }
        }
    }
}
