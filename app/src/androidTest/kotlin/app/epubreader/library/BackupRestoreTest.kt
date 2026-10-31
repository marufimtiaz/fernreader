package app.epubreader.library

import android.content.Intent
import android.net.Uri
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
import app.epubreader.core.data.SettingsStore
import app.epubreader.core.library.ScanScope
import app.epubreader.ui.Copy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-090 check: backup → clear app data → rescan → restore → positions, collections (and hidden books, settings) return.
 * "Clear app data" is every table emptied in-process (pm clear would kill the test); the restore comes from opening
 * the backup file as a file manager would (F-090 "opening a backup file from a file manager offers restore").
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val dir = File(Environment.getExternalStorageDirectory(), "Download/m7-backup-test")

    @Before fun setUp() {
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        dir.deleteRecursively(); dir.mkdirs()
        File(dir, "alice.epub").writeBytes(TestBooks.epub(id = "urn:uuid:m7-alice", title = "M7 Alice"))
        File(dir, "frank.epub").writeBytes(TestBooks.epub(id = "urn:uuid:m7-frank", title = "M7 Frankenstein"))
        File(dir, "moby.epub").writeBytes(TestBooks.epub(id = "urn:uuid:m7-moby", title = "M7 Moby"))
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private suspend fun scan() = graph.scanner.scan(ScanScope.Folder(dir.path))
    private suspend fun idOf(name: String) = graph.db.libraryDao().bookIdForPath(File(dir, name).path)!!

    @Test fun backup_clear_rescan_restore(): Unit = runBlocking {
        scan()
        val alice = idOf("alice.epub"); val frank = idOf("frank.epub"); val moby = idOf("moby.epub")
        graph.db.positionDao().put(alice, "epubcfi(/6/4!/4/2/1:11)", 0.4, 1, graph.clock.now())
        val c = graph.library.createCollection("M7 Classics")
        graph.library.addToCollection(c, listOf(alice, frank))
        graph.library.hide(listOf(moby))
        graph.settings.put(SettingsStore.LIBRARY_SORT, "AUTHOR")
        val backup = File(dir, "epub-reader-backup-test.json").apply { writeText(graph.backup.export("test")) }

        graph.db.clearAllTables() // "clear app data"
        scan() // grant + rescan: new book ids
        val alice2 = idOf("alice.epub"); val frank2 = idOf("frank.epub")
        assertEquals(null, graph.library.position(alice2))

        ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_VIEW, Uri.fromFile(backup), ctx, MainActivity::class.java)).use {
            assertNotNull(device.wait(Until.findObject(By.text(Copy.restoreTitle)), 10_000))
            device.findObject(By.text(Copy.restore)).click()
            assertNotNull(device.wait(Until.findObject(By.text(Copy.restoredTitle)), 10_000))
            device.findObject(By.text(Copy.ok)).click()
        }
        assertEquals("epubcfi(/6/4!/4/2/1:11)", graph.library.position(alice2)!!.cfi)
        val cid = graph.library.collections().first().single { it.name == "M7 Classics" }.id
        assertEquals(setOf(alice2, frank2), graph.library.library().first().filter { cid in it.collectionIds }.map { it.id }.toSet())
        assertEquals(listOf("M7 Moby"), graph.library.hiddenBooks().first().map { it.title })
        assertEquals("AUTHOR", graph.settings.get(SettingsStore.LIBRARY_SORT))
    }
}
