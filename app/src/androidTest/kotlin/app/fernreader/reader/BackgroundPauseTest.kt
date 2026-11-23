package app.fernreader.reader

import android.os.Environment
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.library.ScanEvent
import app.fernreader.core.library.ScanScope
import app.fernreader.reader.bridge.ToWeb
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** C-113: the reader page does no work in the background and comes back where it was; unwaited work runs at background priority. */
@RunWith(AndroidJUnit4::class)
class BackgroundPauseTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph

    /**
     * (a): stopped (HOME), the page is paused: a page timer does not run at all (unpaused, a hidden page's timers still
     * run about once a second). Back in the foreground the timer runs again and the reader shows the same page: no
     * relocate, the same location and page in the section.
     */
    @Test fun the_page_is_paused_in_the_background_and_resumes_on_the_same_page(): Unit = runBlocking {
        val f = TestBooks.write(ctx, "pause-${System.nanoTime()}.epub", TestBooks.epub(id = "urn:uuid:pause-${System.nanoTime()}", chapters = 3, paragraphs = 60))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                var here = h.open(f)
                repeat(3) { val from = here; h.send(ToWeb.Next()); here = h.relocate { it.cfi != from.cfi } }
                here = h.quiet()
                val page = sc.js("JSON.stringify(window.__readerTest.page())")
                sc.js("window.__ticks = []; window.__tick = setInterval(() => window.__ticks.push(Date.now()), 100); true")

                sc.moveToState(Lifecycle.State.CREATED) // HOME: stopped
                sc.onActivity { assertTrue("paused when stopped", it.reader.paused) }
                val stoppedAt = System.currentTimeMillis()
                delay(4_000)
                val resumingAt = System.currentTimeMillis()
                sc.moveToState(Lifecycle.State.RESUMED)
                sc.onActivity { assertFalse("running again in the foreground", it.reader.paused) }
                delay(600)

                val ticks = JSONArray(sc.js("window.__ticks")).let { a -> List(a.length()) { a.getLong(it) } }
                sc.js("clearInterval(window.__tick); true")
                val inBackground = ticks.filter { it in stoppedAt + 500..resumingAt - 500 }
                assertEquals("page timer ran while in the background: $inBackground", emptyList<Long>(), inBackground)
                assertTrue("page timer runs again after the return: $ticks", ticks.any { it > resumingAt })

                h.still(); h.drain()
                assertEquals("same location after the return", here.cfi, h.at!!.cfi)
                assertEquals("same page after the return", page, sc.js("JSON.stringify(window.__readerTest.page())"))
            } finally {
                h.stop()
            }
        }
    }

    /** (b): the background dispatcher's threads, and a library scan's loaders on them, run at background priority. */
    @Test fun scans_and_background_work_run_at_background_priority(): Unit = runBlocking {
        // All-files access; reading the output to its end waits until the command has run.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")).use { it.readBytes() }
        assertEquals(Process.THREAD_PRIORITY_BACKGROUND, withContext(graph.background) { Process.getThreadPriority(Process.myTid()) })
        val dir = File(Environment.getExternalStorageDirectory(), "Download/r-priority")
        try {
            dir.deleteRecursively(); dir.mkdirs()
            TestBooks.write(ctx, "prio.epub", TestBooks.epub(id = "urn:uuid:prio-${System.nanoTime()}")).copyTo(File(dir, "prio.epub"))
            val seen = ConcurrentHashMap<String, Int>() // event kind -> priority of the thread it came from
            val done = graph.scanner.scan(ScanScope.Folder(dir.path)) { e -> seen[e::class.simpleName!!] = Process.getThreadPriority(Process.myTid()) }
            assertEquals("the new book was loaded", 1, done.added)
            assertTrue("a loader reported the book: $seen", seen.containsKey("BookAdded"))
            assertEquals("scan threads: $seen", setOf(Process.THREAD_PRIORITY_BACKGROUND), seen.values.toSet())
            assertTrue(seen.keys.containsAll(listOf(ScanEvent.Started::class.simpleName, ScanEvent.Complete::class.simpleName)))
        } finally {
            if (dir.exists()) { dir.deleteRecursively(); graph.scanner.scan(ScanScope.Folder(dir.parent!!)) } // prune its rows
        }
    }
}
