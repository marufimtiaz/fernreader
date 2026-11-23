package app.fernreader.library

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import app.fernreader.reader.bridge.ToWeb
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.SourceRef
import app.fernreader.reader.ReaderError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** R-008: the open book's file is replaced or deleted → message, book closed, no crash. */
@RunWith(AndroidJUnit4::class)
class FileReplacedUnderReaderTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val shared = File(Environment.getExternalStorageDirectory(), "Download/r8-test")

    @After fun cleanUp() { shared.deleteRecursively() }

    /**
     * [onSharedStorage]: the book lies in /sdcard (FUSE), as books do, instead of the app's cache directory. FUSE sends no
     * inotify event for the change, so the app sees it at the next check (C-108 b): [notice] makes one happen.
     */
    private fun underReader(name: String, onSharedStorage: Boolean = false, notice: (ActivityScenario<MainActivity>, MainActivity) -> Unit = { _, _ -> }, change: (File) -> Unit): Unit = runBlocking {
        val bytes = TestBooks.epub(id = "urn:uuid:$name", chapters = 3, paragraphs = 60)
        val f = if (!onSharedStorage) TestBooks.write(ctx, name, bytes) else {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
            shared.mkdirs(); File(shared, name).apply { writeBytes(bytes) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            val opened = withTimeout(20_000) { a.controller.state.first { it.open && !it.loading } }
            assertNull("the open itself failed", opened.error) // C-049: a failed open used to read as a wrong R-008 result
            change(f)
            notice(sc, a)
            val s = withTimeout(5_000) { a.controller.state.first { it.error != null } }
            assertEquals(ReaderError.FileChanged, s.error)
            sc.onActivity { it.goBack() } // back to the library, still running
            Unit
        }
    }

    @Test fun replaced(): Unit = underReader("r8-replace.epub") { it.writeBytes(TestBooks.epub(id = "urn:uuid:other", title = "Other")) }
    @Test fun deleted(): Unit = underReader("r8-delete.epub") { it.delete() }
    /** Seen when the app comes back to the foreground. */
    @Test fun deleted_on_shared_storage(): Unit = underReader("r8-sd-delete.epub", onSharedStorage = true, notice = { sc, _ ->
        sc.moveToState(Lifecycle.State.CREATED); sc.moveToState(Lifecycle.State.RESUMED)
    }) { it.delete() }
    /** Seen before the next position is stored (a page turn). */
    @Test fun replaced_by_rename_on_shared_storage(): Unit = underReader("r8-sd-rename.epub", onSharedStorage = true, notice = { _, a ->
        a.runOnUiThread { a.reader.send(ToWeb.Next()) }
    }) { f ->
        val tmp = File(f.parentFile, "r8-sd-rename.tmp").apply { writeBytes(TestBooks.epub(id = "urn:uuid:renamed-over-sd")) }
        check(tmp.renameTo(f))
    }
    @Test fun replaced_by_rename(): Unit = underReader("r8-rename.epub") { f ->
        val tmp = File(f.parentFile, "r8-rename.tmp").apply { writeBytes(TestBooks.epub(id = "urn:uuid:renamed-over")) }
        check(tmp.renameTo(f))
    }
}
