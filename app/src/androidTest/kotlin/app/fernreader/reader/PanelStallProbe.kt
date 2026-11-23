package app.fernreader.reader

import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.fernreader.MainActivity
import app.fernreader.SlideFrames
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A measurement, not part of the suite's checks: one slow swipe from the edge, the panel's first open in this process, with
 * the main thread's messages timed (the looper's own log) and the slide's frames recorded. Prints one `StallProbe` line:
 * the longest main-thread message during the swipe, the time from the gesture's recognition (the first held position) to
 * the panel's first frame on screen, and that first frame's edge. Each method is meant to run in its own process
 * (`am instrument -e class ...#method`), so that it is the panel's first composition, as after an app start; a trace
 * (atrace/Perfetto) taken around it shows the app's sections (Contents:compose, Toc:tree, ...).
 *
 * Books: War and Peace (a long contents list, 380 entries) and Alice (12), from the pushed corpus.
 *
 * It checks nothing, so the suite skips it: run it with `-e probe 1`.
 */
@RunWith(AndroidJUnit4::class)
class PanelStallProbe {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    @Before fun onlyWhenAsked() = assumeTrue("a measurement: -e probe 1", InstrumentationRegistry.getArguments().getString("probe") == "1")

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }.trim()

    /** The main thread's messages longer than 8 ms while [block] runs: (start, ms, what). */
    private suspend fun messagesDuring(block: suspend () -> Unit): List<Triple<Long, Long, String>> {
        val long = java.util.Collections.synchronizedList(mutableListOf<Triple<Long, Long, String>>())
        var start = 0L
        var what = ""
        Looper.getMainLooper().setMessageLogging { line ->
            if (line.startsWith(">>>>> Dispatching")) { start = SystemClock.uptimeMillis(); what = line }
            else if (line.startsWith("<<<<< Finished")) {
                val ms = SystemClock.uptimeMillis() - start
                if (ms > 8) long += Triple(start, ms, what.substringAfter("} ").take(120))
            }
        }
        try { block() } finally { Looper.getMainLooper().setMessageLogging(null) }
        return long.toList()
    }

    private fun report(name: String, frames: SlideFrames, msgs: List<Triple<Long, Long, String>>, held: Long?) {
        val shown = frames.frames.filter { it.edge > 0.5f }
        val first = shown.firstOrNull()
        val worst = msgs.maxByOrNull { it.second }
        val line = "StallProbe $name: longest main-thread message ${worst?.second ?: 0} ms (${worst?.third}); " +
            "recognised->first panel frame ${if (held != null && first != null) first.t - held else -1} ms; first frame edge ${first?.edge?.toInt()} px; " +
            "messages >8ms: ${msgs.joinToString { "${it.second}" }}; panel edges: ${shown.take(12).joinToString(" ") { "%.0f".format(it.edge) }}"
        Log.i("StallProbe", line)
        println(line)
    }

    private fun contents(book: File, name: String) = runBlocking {
        shell("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow")
        check(book.isFile) { "corpus not pushed: $book" }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                h.open(book)
                h.quiet()
                delay(1_000)
                // The first open in this process, then a second one: the second shows what is left once the code is warm.
                for (n in listOf(name, "$name (2nd)")) {
                    var held: Long? = null
                    val watch = launch { h.a.controller.contentsDrag.first { it != null }; held = SystemClock.uptimeMillis() }
                    val frames = SlideFrames(inst, "contents")
                    frames.record()
                    Trace.beginSection("probe:swipe $n")
                    val msgs = messagesDuring { frames.drag(dev.displayWidth - 12, dev.displayHeight / 2, dir = -1) }
                    Trace.endSection()
                    watch.cancel()
                    report(n, frames, msgs, held)
                    delay(800)
                    sc.onActivity { it.controller.hideToc() }
                    delay(1_500)
                }
            } finally { h.stop() }
        }
    }

    @Test fun contents_long_toc() = contents(File("/sdcard/Books/gutenberg/war-and-peace-epub3.epub"), "contents war-and-peace")
    @Test fun contents_small_toc() = contents(File("/sdcard/Books/gutenberg/alice-epub3-images.epub"), "contents alice")

    @Test fun drawer_first_open() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.library.ui.first { it.loaded && !it.scan.running } }
            delay(1_500)
            val frames = SlideFrames(inst, "drawer")
            frames.record()
            Trace.beginSection("probe:swipe drawer")
            val msgs = messagesDuring { frames.drag(12, dev.displayHeight / 2, dir = 1) }
            Trace.endSection()
            report("drawer", frames, msgs, null)
            delay(800)
            sc.onActivity { it.library.setDrawer(false) }
            delay(500)
        }
    }
}
