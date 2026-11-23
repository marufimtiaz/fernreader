package app.fernreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.fernreader.MainActivity
import app.fernreader.core.data.Flow
import app.fernreader.core.data.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A Contents jump in scroll mode lands and stays, checked on the screen (a hit test at the top of the viewport), when it
 * comes while the scroll momentum of a fling is still running, and while stacked sections are still loading.
 */
@RunWith(AndroidJUnit4::class)
class JumpDuringFlingTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val targets = listOf("Preface", "2. Building", "Acknowledgments", "1. Introduction")

    private fun fling() = dev.swipe(dev.displayWidth / 2, dev.displayHeight * 4 / 5, dev.displayWidth / 2, dev.displayHeight / 5, 4)

    /** Section and text at the top of the screen, and the section the reader counts as current. */
    private suspend fun ReaderHarness.screen(): Triple<Int, String, Int> {
        val top = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.screenTop())")).nextValue() as String)
        val current = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.slots())")).nextValue() as String).getInt("current")
        return Triple(top.getInt("index"), top.getString("text"), current)
    }

    private suspend fun ReaderHarness.jump(label: String) {
        val item = a.controller.state.value.toc.first { it.label.contains(label, ignoreCase = true) }
        drain()
        sc.onActivity { it.controller.onTocSelected(item) }
        relocate(10_000) { it.sectionIndex == item.index }
    }

    private fun run(block: suspend ReaderHarness.(Map<String, Triple<Int, String, Int>>) -> Unit) = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(File("/sdcard/Books/epub3-samples/accessible_epub_3.epub"))
                withTimeout(15_000) { h.a.controller.state.first { it.title.contains("Accessible", ignoreCase = true) && it.toc.isNotEmpty() } }
                h.settle()
                sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = Flow.SCROLLED) } }
                h.still()
                // where each entry lands with nothing going on
                val clean = targets.associateWith { t -> h.jump(t); h.still(); h.screen() }
                h.block(clean)
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.a.settingsStored(); h.stop() }
        }
    }

    @Test fun jumps_during_a_flings_run_out_land_and_stay() = run { clean ->
        val wrong = mutableListOf<String>()
        val waits = listOf(900L, 1000L, 1100L, 1200L, 1300L, 1400L, 1500L, 1000L)
        waits.forEachIndexed { k, wait ->
            val target = targets[k % targets.size]
            fling()
            delay(wait) // into the fling's run-out, which lasts until about 2.5 s after the finger lifts
            jump(target)
            delay(2_000) // past the end of the run-out
            val landed = screen()
            delay(1_000) // the check that it stays there: fixed
            val later = screen()
            if (landed != clean[target] || later != landed) wrong += "jump to $target ${wait} ms after a fling: ${clean[target]}, got $landed then $later"
        }
        assertEquals("wrong ${wrong.size} of ${waits.size}", emptyList<String>(), wrong)
    }

    @Test fun a_jump_while_stacked_sections_load_lands() = run { clean ->
        val wrong = mutableListOf<String>()
        for (wait in listOf(0L, 100L, 200L, 250L, 300L, 350L, 400L, 500L)) {
            val first = targets[0]; val target = targets[1]
            jump(first)
            delay(wait) // the stack starts loading the next sections when the jump's flags drop, up to 200 ms after it
            jump(target)
            still()
            val landed = screen()
            if (landed != clean[target]) wrong += "second jump $wait ms after the first: ${clean[target]}, got $landed"
        }
        assertEquals("wrong ${wrong.size}", emptyList<String>(), wrong)
    }
}
