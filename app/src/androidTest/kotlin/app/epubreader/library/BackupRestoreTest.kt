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
import androidx.room.useWriterConnection
import app.epubreader.core.data.AnnotationStyle
import app.epubreader.core.data.HighlightColors
import app.epubreader.core.data.SettingsStore
import app.epubreader.reader.ReaderHarness
import kotlinx.coroutines.withTimeout
import app.epubreader.core.library.ScanScope
import app.epubreader.ui.Copy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-090 check: backup → clear app data → rescan → restore → positions, bookmarks, highlights and notes, collections (and
 * hidden books, settings) return.
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
        // Each test starts from cleared app data: the same files keep their book ids across runs, so rows an earlier run
        // restored (a collection, a bookmark on the first page) would otherwise be found again.
        graph.db.clearAllTables()
    }

    @After fun tearDown() { dir.deleteRecursively() }

    companion object {
        /**
         * C-049: the tests clear every table. Leave the run's library as it was prepared (one scan of the device), so later
         * classes neither find this class's collections, settings and hidden books nor pay for a background full rescan.
         */
        @AfterClass @JvmStatic fun restoreTheLibrary(): Unit = runBlocking {
            val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EpubReaderApp).graph
            graph.db.clearAllTables()
            graph.scanner.scan(ScanScope.Everywhere)
        }
    }

    private suspend fun scan() = graph.scanner.scan(ScanScope.Folder(dir.path))
    private suspend fun idOf(name: String) = graph.db.libraryDao().bookIdForPath(File(dir, name).path)!!

    /**
     * R-009: a backup written by an older app version (the frozen format-version-1 fixture of core:data, written by M7)
     * restores on this build through the real open-with path: its collection and setting come back, and its books wait
     * for their files (none of them is on this device).
     */
    @Test fun an_old_version_1_backup_restores(): Unit = runBlocking {
        val old = File(dir, "backup-from-m7.json")
        inst.context.assets.open("v1.json").use { input -> old.outputStream().use { input.copyTo(it) } }
        ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_VIEW, Uri.fromFile(old), ctx, MainActivity::class.java)).use {
            assertNotNull(device.wait(Until.findObject(By.text(Copy.restoreTitle)), 10_000))
            device.findObject(By.text(Copy.restore)).click()
            assertNotNull(device.wait(Until.findObject(By.text(Copy.restoredTitle)), 10_000))
            device.findObject(By.text(Copy.ok)).click()
        }
        assertEquals(listOf("Classics"), graph.library.collections().first().map { it.name })
        assertEquals("LIST", graph.settings.get(SettingsStore.LIBRARY_LAYOUT))
    }

    @Test fun backup_clear_rescan_restore(): Unit = runBlocking {
        scan()
        val alice = idOf("alice.epub"); val frank = idOf("frank.epub"); val moby = idOf("moby.epub")
        graph.db.positionDao().put(alice, "epubcfi(/6/4!/4/2/1:11)", 0.4, 1, graph.clock.now())
        val c = graph.library.createCollection("M7 Classics")
        graph.library.addToCollection(c, listOf(alice, frank))
        graph.library.hide(listOf(moby))
        graph.settings.put(SettingsStore.LIBRARY_SORT, "AUTHOR")
        graph.bookmarks.add(alice, "epubcfi(/6/4!/4/2/1:11)", 1, 0.4, "Chapter 1")
        val h = graph.annotations.add(alice, "epubcfi(/6/4!/4/2/4,/1:0,/1:9)", 1, "Paragraph", "Chapter 1", HighlightColors.GREEN, AnnotationStyle.HIGHLIGHT)
        graph.annotations.update(h.id, note = "M6 note")
        val backup = File(dir, "fernreader-backup-test.json").apply { writeText(graph.backup.export("test")) }

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
        assertEquals(listOf("epubcfi(/6/4!/4/2/1:11)"), graph.bookmarks.bookmarks(alice2).first().map { it.cfi })
        assertEquals(listOf("M6 note"), graph.annotations.annotations(alice2).first().map { it.note })
    }

    /**
     * M6 + M7: a bookmark, a highlight and a note made in the reader, lost (rows deleted outright, as on a device whose data
     * was cleared), come back from a backup into the book that is open: the reader's lists follow the database.
     */
    @Test fun bookmarks_highlights_and_notes_come_back_into_the_open_reader(): Unit = runBlocking {
        scan()
        val alice = idOf("alice.epub")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                h.open(File(dir, "alice.epub"))
                val c = h.a.controller
                val at = h.at!!
                suspend fun until(what: String, ok: (app.epubreader.reader.ReaderUiState) -> Boolean) =
                    try { withTimeout(10_000) { c.state.first(ok) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        val st = c.state.value
                        throw AssertionError("$what: bookmarks=${st.bookmarks.size} annotations=${st.annotations.size} bookmarked=${st.bookmarked}")
                    }
                sc.onActivity { c.toggleBookmark() } // the controls' bookmark button
                val hl = graph.annotations.add(alice, "epubcfi(/6/4!/4/2/4,/1:0,/1:9)", at.sectionIndex, "Paragraph", "Chapter 1", HighlightColors.BLUE, AnnotationStyle.HIGHLIGHT)
                val note = graph.annotations.add(alice, "epubcfi(/6/4!/4/2/6,/1:0,/1:9)", at.sectionIndex, "Paragraph", "Chapter 1", HighlightColors.PINK, AnnotationStyle.UNDERLINE)
                graph.annotations.update(note.id, note = "M6 note")
                until("made") { it.bookmarks.size == 1 && it.annotations.size == 2 && it.bookmarked }
                val bookmark = c.state.value.bookmarks.single()
                val text = graph.backup.export("test")

                graph.db.useWriterConnection { t ->
                    t.usePrepared("DELETE FROM bookmark") { it.step() }
                    t.usePrepared("DELETE FROM annotation") { it.step() }
                }
                until("deleted") { it.bookmarks.isEmpty() && it.annotations.isEmpty() && !it.bookmarked }

                val r = graph.backup.restore(text) // the app's backup API (the system file picker is not driven here)
                assertEquals(1, r.bookmarks); assertEquals(2, r.annotations)
                val s = until("restored") { it.bookmarks.size == 1 && it.annotations.size == 2 }
                assertEquals(bookmark, s.bookmarks.single())
                assertEquals(setOf(hl.id, note.id), s.annotations.map { it.id }.toSet())
                assertEquals("M6 note", s.annotations.single { it.id == note.id }.note)
                assertEquals(HighlightColors.BLUE, s.annotations.single { it.id == hl.id }.color)
                until("marker") { it.bookmarked } // the page marker is back
            } finally { h.stop() }
        }
    }
}
