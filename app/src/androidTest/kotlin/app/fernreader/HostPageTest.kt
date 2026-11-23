package app.fernreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.reader.BookContentServer
import app.fernreader.reader.ReaderEngine
import app.fernreader.reader.bridge.FromWeb
import app.fernreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostPageTest {
    @Test fun host_page_loads_and_answers_ping() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        lateinit var engine: ReaderEngine
        inst.runOnMainSync { engine = ReaderEngine(inst.targetContext, BookContentServer()).also { it.prewarm() } }
        withTimeout(10_000) { engine.isReady.first { it } }
        // Subscribe before sending: events has no replay.
        val pong = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { engine.events.filterIsInstance<FromWeb.Pong>().first() }
        }
        engine.send(ToWeb.Ping(id = 42))
        assertEquals(42, pong.await().id)
        inst.runOnMainSync { engine.destroy() }
    }
}
