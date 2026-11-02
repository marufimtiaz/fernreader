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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-024 / F-026 checks through the real screen: long-press, select, add to a new collection "Classics" → the
 * collection shows exactly those books; F-025: delete from the library only keeps the file and hides the book.
 */
@RunWith(AndroidJUnit4::class)
class CollectionsUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val dir = File(Environment.getExternalStorageDirectory(), "Download/m7-ui-test")

    @Before fun setUp() {
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        dir.deleteRecursively(); dir.mkdirs()
        listOf("Alice" to "alice", "Frankenstein" to "frank", "Moby" to "moby").forEach { (t, n) ->
            File(dir, "$n.epub").writeBytes(TestBooks.epub(id = "urn:uuid:m7ui-$n", title = "Zq $t"))
        }
        runBlocking {
            graph.scanner.scan(ScanScope.Folder(dir.path))
            graph.library.collections().first().filter { it.name == "Classics" }.forEach { graph.library.deleteCollection(it.id) }
        }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun text(t: String) = checkNotNull(device.wait(Until.findObject(By.text(t)), 10_000)) { "not shown: $t" }

    @Test fun classics_with_alice_and_frankenstein_then_library_only_delete(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            checkNotNull(device.wait(Until.findObject(By.desc(Copy.search)), 10_000)) { "no search icon" }.click() // C-077: the bar's search icon
            checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)) { "no search field" }.text = "Zq "
            text("Zq Alice").longClick()
            text("Zq Frankenstein").click()
            text(Copy.selectedCount(2))
            text(Copy.addToCollectionShort).click()
            text(Copy.newCollection).click()
            device.wait(Until.findObject(By.clazz("android.widget.EditText").focused(true)), 2_000)
            device.findObjects(By.clazz("android.widget.EditText")).last().text = "Classics"
            text(Copy.save).click()
            val c = withTimeout(5_000) { graph.library.collections().first { l -> l.any { it.name == "Classics" } } }.single { it.name == "Classics" }
            withTimeout(5_000) { graph.library.library().first { l -> l.count { c.id in it.collectionIds } == 2 } }
            checkNotNull(device.wait(Until.findObject(By.desc(Copy.openMenu)), 5_000)).click()
            text("Classics").click()
            assertTrue(device.wait(Until.hasObject(By.text("Zq Alice")), 5_000))
            assertTrue(device.hasObject(By.text("Zq Frankenstein")))
            assertTrue("Moby not in Classics", device.wait(Until.gone(By.text("Zq Moby")), 5_000)) // the search list it replaced had it (no idle wait, C-049)

            // F-025 library-only delete from the collection view
            text("Zq Alice").longClick()
            checkNotNull(device.wait(Until.findObject(By.desc(Copy.delete)), 5_000)).click()
            text(Copy.remove).click()
            withTimeout(5_000) { graph.library.hiddenBooks().first { l -> l.any { it.title == "Zq Alice" } } }
            assertTrue(File(dir, "alice.epub").exists())
            graph.scanner.scan(ScanScope.Folder(dir.path))
            assertTrue(graph.library.library().first().none { it.title == "Zq Alice" })
            graph.library.unhide(graph.library.hiddenBooks().first().filter { it.title == "Zq Alice" }.map { it.id })
            assertTrue(graph.library.library().first().any { it.title == "Zq Alice" })
            sc.onActivity { }
        }
    }
}
