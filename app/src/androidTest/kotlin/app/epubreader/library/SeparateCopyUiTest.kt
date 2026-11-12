package app.epubreader.library

import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.library.ScanScope
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
import app.epubreader.waitFresh
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-096 through the real Book details: Keep as a separate copy, a rename and a rescan on real storage, then Merge with
 * the other copy, whose warning names the reading data that goes.
 */
@RunWith(AndroidJUnit4::class)
class SeparateCopyUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val dir = File(Environment.getExternalStorageDirectory(), "Download/c096-ui-test")
    private val first = File(dir, "a/first.epub")
    private val second = File(dir, "b/second.epub")
    private val title = "Zq Separate Copy"

    @Before fun setUp() {
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        dir.deleteRecursively()
        val bytes = TestBooks.epub(id = "urn:uuid:c096-ui", title = title)
        for (f in listOf(first, second)) { f.parentFile!!.mkdirs(); f.writeBytes(bytes) }
        runBlocking { graph.scanner.scan(ScanScope.Everywhere) }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        runBlocking { graph.scanner.scan(ScanScope.Everywhere) }
    }

    private fun text(t: String) = checkNotNull(device.wait(Until.findObject(By.text(t)), 10_000)) { "not shown: $t" }
    private suspend fun entries() = graph.library.library().first().filter { it.title == title }

    @Test fun keep_rename_rescan_then_merge(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            val original = entries().single().id
            graph.positions.await()
            sc.onActivity { it.nav.push(Route.BookDetails(original)) }

            text(Copy.fileLocations(2))
            text(Copy.keepSeparate).click()
            assertTrue("the entry still lists two files", waitFresh(10_000) { device.hasObject(By.text(Copy.fileLocations(1))) })
            assertEquals(2, entries().size)
            val copy = graph.library.knownBookId(app.epubreader.core.data.SourceRef.File(second.path))!!
            assertTrue(copy != original)
            assertNull(graph.library.position(copy)) // starts fresh
            device.pressBack()

            // renamed on real storage: the same content, a new name, and the copy keeps it
            val renamed = File(dir, "b/renamed.epub")
            assertTrue(second.renameTo(renamed))
            graph.scanner.scan(ScanScope.Everywhere)
            assertEquals(copy, graph.library.knownBookId(app.epubreader.core.data.SourceRef.File(renamed.path)))
            assertEquals(2, entries().size)

            graph.bookmarks.add(copy, "epubcfi(/6/2!/4/2/1:0)", 0, 0.1, "One")
            graph.bookmarks.add(copy, "epubcfi(/6/2!/4/2/1:9)", 0, 0.2, "One")
            sc.onActivity { it.nav.push(Route.BookDetails(copy)) }

            text(Copy.separateCopyNote)
            text(Copy.mergeCopy).click()
            text(Copy.mergeCopyWarning(position = false, bookmarks = 2, highlights = 0, notes = 0))
            text(Copy.cancel).click()
            assertEquals(2, entries().size)
            assertTrue("the warning stayed", waitFresh(5_000) { !device.hasObject(By.text(Copy.mergeCopyTitle)) })

            text(Copy.mergeCopy).click()
            text(Copy.mergeCopyConfirm).click()
            assertTrue("the page stayed open", waitFresh(10_000) { a.nav.top != Route.BookDetails(copy) })
            assertEquals(listOf(original), entries().map { it.id })
            assertEquals(listOf(first.path, renamed.path).sorted(), entries().single().paths.sorted())
            assertEquals(emptyList<Any>(), graph.bookmarks.bookmarks(copy).first())
        }
    }
}
