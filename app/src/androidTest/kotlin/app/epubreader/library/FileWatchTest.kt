package app.epubreader.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
            Thread.sleep(1_600) // main held: the watch notices (inotify, then the 1 s poll) and posts its callback
            w.stop() // the book is closed or another one opened, before that callback runs
        }
        inst.waitForIdleSync()
        Thread.sleep(300)
        assertFalse("callback ran after stop()", called.get())
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
