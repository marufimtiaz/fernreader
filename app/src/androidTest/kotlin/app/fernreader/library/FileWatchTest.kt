package app.fernreader.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/** R-008 watch: a change seen before stop() must not reach the reader after it (review I-5: it closed the next book). */
@RunWith(AndroidJUnit4::class)
class FileWatchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun book(name: String) = File(inst.targetContext.cacheDir, name).apply { writeBytes(ByteArray(64) { 1 }) }

    @Test fun a_change_noticed_before_stop_is_not_delivered_after_it() {
        val f = book("fw-stop.epub")
        val called = AtomicBoolean(false)
        inst.runOnMainSync {
            val w = FileWatch(f.path) { called.set(true) }.start()
            f.delete()
            Thread.sleep(600) // main held: the watch notices (inotify) and posts its callback
            w.stop() // the book is closed or another one opened, before that callback runs
        }
        inst.waitForIdleSync()
        Thread.sleep(300)
        assertFalse("callback ran after stop()", called.get())
    }

    /**
     * C-108 (b): nothing checks the file by itself; a check notices a rewrite. A new mtime on the same file is an ATTRIB
     * event the watch ignores (same inode), so only a check sees it (the old 1 s poll did within a second).
     */
    @Test fun only_a_check_notices_a_rewrite_inotify_ignores(): Unit = runBlocking {
        val f = book("fw-check.epub")
        val called = AtomicBoolean(false)
        lateinit var w: FileWatch
        inst.runOnMainSync { w = FileWatch(f.path) { called.set(true) }.start() }
        try {
            check(f.setLastModified(f.lastModified() - 60_000))
            Thread.sleep(2_500)
            assertFalse("noticed without a check: something polls", called.get())
            assertFalse("the check sees the rewrite", w.unchanged())
            val end = System.currentTimeMillis() + 2_000
            while (!called.get() && System.currentTimeMillis() < end) Thread.sleep(20)
            assertTrue("the reader is told", called.get())
        } finally { inst.runOnMainSync { w.stop() } }
    }

    @Test fun a_change_is_delivered_while_watching() {
        val f = book("fw-live.epub")
        val called = AtomicBoolean(false)
        lateinit var w: FileWatch
        inst.runOnMainSync { w = FileWatch(f.path) { called.set(true) }.start() }
        f.delete()
        val end = System.currentTimeMillis() + 5_000
        while (!called.get() && System.currentTimeMillis() < end) Thread.sleep(50)
        inst.runOnMainSync { w.stop() }
        assertTrue(called.get())
    }
}
