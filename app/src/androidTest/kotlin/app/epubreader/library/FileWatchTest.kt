package app.epubreader.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Environment
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
            Thread.sleep(600) // main held: the watch notices (inotify) and posts its callback
            w.stop() // the book is closed or another one opened, before that callback runs
        }
        inst.waitForIdleSync()
        Thread.sleep(300)
        assertFalse("callback ran after stop()", called.get())
    }

    /** C-108 (b): on shared storage (FUSE: no inotify event) a check notices a delete; nothing polls meanwhile. */
    @Test fun a_check_notices_a_change_on_shared_storage() {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        val dir = File(Environment.getExternalStorageDirectory(), "Download/fw-test").apply { mkdirs() }
        try {
            val f = File(dir, "fw-check.epub").apply { writeBytes(ByteArray(64) { 1 }) }
            val called = AtomicBoolean(false)
            lateinit var w: FileWatch
            inst.runOnMainSync { w = FileWatch(f.path) { called.set(true) }.start() }
            f.delete()
            Thread.sleep(2_500)
            assertFalse("noticed without a check: a timer polls again, or FUSE sent an event", called.get())
            w.checkNow()
            val end = System.currentTimeMillis() + 2_000
            while (!called.get() && System.currentTimeMillis() < end) Thread.sleep(20)
            inst.runOnMainSync { w.stop() }
            assertTrue("the check noticed the delete", called.get())
        } finally { dir.deleteRecursively() }
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
