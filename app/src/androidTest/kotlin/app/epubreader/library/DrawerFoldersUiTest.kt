package app.epubreader.library

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.library.ScanScope
import app.epubreader.ui.Copy
import app.epubreader.waitFresh
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
 * C-074 (F-017 checks) through the drawer, on the folder set placed by bench/push_corpus.sh: the drawer's folder
 * section lists `Download` and both `Books` folders, told apart by their parent path; tapping `Download` shows exactly
 * the books directly in it; after `Books/ToDelete/` is deleted and the library rescanned, it is gone from the drawer.
 * `ToDelete/deletedfolder.epub` is put back afterwards (FolderSetTest expects it).
 */
@RunWith(AndroidJUnit4::class)
class DrawerFoldersUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val sd = "/storage/emulated/0"
    private val toDelete = File("$sd/Books/ToDelete")
    private val deleted = File(toDelete, "deletedfolder.epub")
    private var saved: ByteArray? = null

    @Before fun setUp() {
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        for (rel in listOf("Download/download.epub", "Documents/Books/documents.epub", "Books/insidecustom.epub", "Books/ToDelete/deletedfolder.epub"))
            check(File("$sd/$rel").isFile) { "folder set not pushed (bench/push_corpus.sh): $rel" }
        saved = deleted.readBytes()
        graph.prefs.setFolderView(true)
        runBlocking { graph.scanner.scan(ScanScope.Everywhere) }
    }

    @After fun tearDown() {
        saved?.let { if (!deleted.isFile) { toDelete.mkdirs(); deleted.writeBytes(it) } }
        runBlocking { graph.scanner.scan(ScanScope.Everywhere) }
    }

    private fun obj(sel: androidx.test.uiautomator.BySelector, what: String, ms: Long = 5_000): UiObject2 =
        checkNotNull(device.wait(Until.findObject(sel), ms)) { "not shown: $what" }

    /** A drawer row, scrolling the drawer once if it is below the screen. */
    private fun row(text: String): UiObject2 = device.wait(Until.findObject(By.text(text)), 3_000) ?: run {
        device.swipe(device.displayWidth / 4, device.displayHeight * 3 / 4, device.displayWidth / 4, device.displayHeight / 4, 20)
        obj(By.text(text), "drawer row $text")
    }

    private fun openDrawer(a: MainActivity) {
        obj(By.desc(Copy.openMenu), Copy.openMenu).click()
        runBlocking { withTimeout(5_000) { a.library.ui.first { it.drawerOpen } } }
    }

    @Test fun drawer_lists_folders_opens_download_and_drops_a_deleted_folder(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.library.ui.first { it.loaded && !it.scan.running && it.drawerFolders.rows.isNotEmpty() } }
            sc.onActivity { it.library.setIncludeSubfolders(false) }
            openDrawer(a)
            row("Download"); row("Books")
            // the top-level Books names its parent (the device's storage) even while Documents is folded
            row(Copy.folderName(sd))
            obj(By.desc(Copy.expandFolder("Documents")), "expand Documents").click()
            val rows = withTimeout(5_000) { a.library.ui.first { u -> u.drawerFolders.rows.count { it.node.name == "Books" } == 2 } }.drawerFolders.rows
            assertEquals(listOf("$sd/Books", "$sd/Documents/Books"), rows.filter { it.node.name == "Books" }.map { it.node.path })
            assertEquals(listOf(Copy.folderName(sd), "Documents"), rows.filter { it.node.name == "Books" }.map { it.parentLine })
            // on screen: "Documents" is the folder's name and the second Books' parent line
            assertTrue(waitFresh(5_000) { device.findObjects(By.text("Documents")).size >= 2 }) // the unfolded rows, not a stale cache
            obj(By.desc(Copy.collapseFolder("Documents")), "collapse Documents").click()

            // Download: exactly the books whose file is directly in it
            row("Download").click()
            val u = withTimeout(5_000) { a.library.ui.first { it.view == LibraryView.Folder("$sd/Download") && !it.drawerOpen } }
            val expected = graph.library.library().first().filter { b -> "$sd/Download" in b.folders }.map { it.id }.toSet()
            assertTrue(expected.isNotEmpty())
            assertEquals(expected, u.books.map { it.id }.toSet())
            assertEquals("Download", u.viewTitle)
            obj(By.text("Folder Test download"), "a book in Download")
            assertTrue("a book from Documents/Books in Download", !device.hasObject(By.text("Folder Test documents")))

            // ToDelete is in the drawer (under Books), then gone after it is deleted and the library rescanned
            openDrawer(a)
            obj(By.desc(Copy.expandFolder("Books")), "expand Books").click()
            row("ToDelete")
            device.pressBack()
            withTimeout(5_000) { a.library.ui.first { !it.drawerOpen } }
            assertTrue(toDelete.deleteRecursively())
            graph.scanner.scan(ScanScope.Everywhere)
            withTimeout(10_000) { a.library.ui.first { u2 -> u2.drawerFolders.rows.none { it.node.path == toDelete.path } } }
            openDrawer(a)
            row("Download")
            assertTrue("ToDelete still in the drawer", device.wait(Until.gone(By.text("ToDelete")), 5_000))
            assertTrue(a.library.ui.value.drawerFolders.rows.single { it.node.path == "$sd/Books" }.node.expanded) // Books still unfolded
        }
    }
}
