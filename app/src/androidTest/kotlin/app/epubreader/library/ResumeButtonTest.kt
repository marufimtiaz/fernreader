package app.epubreader.library

import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.BookEntity
import app.epubreader.core.data.SettingsStore
import app.epubreader.core.data.SourceRef
import app.epubreader.core.library.ScanScope
import app.epubreader.reader.ReaderHarness
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
import app.epubreader.settled
import app.epubreader.waitFresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-092 through the real screen: no history, no button; a book read and left is reopened by the button at its position;
 * the button is in every view, gone while selecting; a deleted last book gives way to the most recent one that exists;
 * the last row of a long list stays clear of the button.
 */
@RunWith(AndroidJUnit4::class)
class ResumeButtonTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val dir = File(Environment.getExternalStorageDirectory(), "Download/c092-resume-test")
    private var history: List<BookEntity> = emptyList()
    private var collection: String? = null

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { p ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(p).readBytes().decodeToString()
    }
    private fun title(n: Int) = "Zr %02d".format(n)
    private fun file(n: Int) = File(dir, "zr-$n.epub")

    @Before fun setUp() {
        shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
        dir.deleteRecursively(); dir.mkdirs()
        for (n in 1..14) file(n).writeBytes(TestBooks.epub(id = "urn:uuid:c092-$n", title = title(n), chapters = 3, paragraphs = 80))
        runBlocking {
            graph.scanner.scan(ScanScope.Folder(dir.path))
            val dao = graph.db.backupDao()
            history = dao.books().filter { it.lastOpenedAt != null }
            setHistory(null) // other tests and the corpus leave books read
            graph.settings.put(SettingsStore.LIBRARY_SORT, LibrarySort.TITLE.name)
            graph.settings.put(SettingsStore.LIBRARY_LAYOUT, LibraryLayout.GRID.name)
        }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        runBlocking {
            collection?.let { graph.library.deleteCollection(it) }
            graph.settings.put(SettingsStore.LIBRARY_SORT, LibrarySort.RECENT.name)
            graph.settings.put(SettingsStore.LIBRARY_LAYOUT, LibraryLayout.GRID.name)
            graph.scanner.scan(ScanScope.Folder(dir.parent!!)) // prune its rows (the deleted folder itself can't be listed)
            setHistory(null)
            val dao = graph.db.backupDao()
            history.forEach { dao.mergeBook(it.id, it.hidden, it.hiddenUpdatedAt, it.addedAt, it.lastOpenedAt, it.updatedAt) }
        }
    }

    private suspend fun setHistory(at: Long?) {
        val dao = graph.db.backupDao()
        dao.books().filter { it.lastOpenedAt != null }.forEach { dao.mergeBook(it.id, it.hidden, it.hiddenUpdatedAt, it.addedAt, at, it.updatedAt) }
    }
    private suspend fun bookId(n: Int) = graph.library.library().first().single { it.title == title(n) }.id
    private suspend fun markRead(n: Int, at: Long) = graph.db.libraryDao().touchOpened(bookId(n), at)

    private suspend fun launched(sc: ActivityScenario<MainActivity>): MainActivity {
        lateinit var a: MainActivity
        sc.onActivity { a = it }
        withTimeout(15_000) { a.library.ui.first { it.loaded && it.total > 0 } }
        return a
    }
    private fun button(n: Int): UiObject2? = device.findObject(By.desc(Copy.resumeBook(title(n))))
    private fun anyButton(): Boolean = device.hasObject(By.descStartsWith(Copy.resume + " "))
    private fun shown(n: Int, why: String): UiObject2 {
        assertTrue("no button for ${title(n)}: $why", waitFresh(5_000) { button(n) != null })
        return button(n)!!
    }
    private fun assertNoButton(why: String) = assertTrue("a button is shown: $why", waitFresh(5_000) { !anyButton() })

    /** Home, [whileAway], then back through the launcher's intent (as in ScanOnReturnTest). */
    private suspend fun awayAndBack(sc: ActivityScenario<MainActivity>, whileAway: () -> Unit) {
        device.pressHome()
        withTimeout(5_000) { while (sc.state != Lifecycle.State.CREATED) delay(20) }
        whileAway()
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${ctx.packageName}/app.epubreader.MainActivity")
    }

    @Test fun no_history_no_button(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val a = launched(sc)
            device.wait(Until.hasObject(By.text(title(1))), 5_000) // the library is on screen
            SystemClock.sleep(500) // the window in which no button may come: fixed
            assertEquals(null, a.library.resume.value)
            assertFalse(waitFresh(0) { anyButton() })
        }
    }

    @Test fun read_a_book_return_and_the_button_reopens_it_at_its_position(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            h.open(file(3))
            h.tocJump("Chapter 2")
            val last = h.lastPage()
            val id = graph.library.knownBookId(SourceRef.File(file(3).path))!!
            device.pressBack()
            withTimeout(5_000) { while (h.a.nav.top != Route.Library) delay(20) }
            withTimeout(3_000) { while (graph.library.position(id)?.cfi != last.cfi) delay(20) } // R-009: stored on leaving (C-108 e)
            val b = shown(3, "after reading it")
            // one node: what TalkBack focuses is what carries the label (a description on a child of the clickable node isn't)
            assertTrue("the labelled node is the clickable one", b.isClickable)
            assertEquals("nothing else to announce", 0, b.childCount)
            assertEquals("the visible label", Copy.resume, b.text)
            h.drain()
            b.click()
            assertEquals(last.cfi, h.relocate(10_000).cfi)
            assertEquals(Route.Reader, h.a.nav.top)
            h.stop()
        }
    }

    @Test fun in_every_view_at_the_bottom_end(): Unit = runBlocking {
        markRead(2, graph.clock.now())
        val c = graph.library.createCollection("Zr Collection").also { collection = it }
        graph.library.addToCollection(c, listOf(bookId(5))) // not the book being offered
        val empty = graph.library.createCollection("Zr Empty")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val a = launched(sc)
            try {
                for (v in listOf(LibraryView.All, LibraryView.Folders, LibraryView.Folder(dir.path), LibraryView.Collection(c), LibraryView.Collection(empty))) {
                    sc.onActivity { it.library.openView(v) }
                    val r = shown(2, "$v").visibleBounds
                    assertTrue("$v: $r", r.right > device.displayWidth * 3 / 4 && r.bottom > device.displayHeight * 3 / 4)
                }
                assertEquals("still in the library", Route.Library, a.nav.top)
            } finally { graph.library.deleteCollection(empty) }
        }
    }

    @Test fun not_while_selecting(): Unit = runBlocking {
        markRead(2, graph.clock.now())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val a = launched(sc)
            sc.onActivity { it.library.openView(LibraryView.Folder(dir.path)) }
            shown(2, "before selecting")
            checkNotNull(device.wait(Until.findObject(By.text(title(1))), 5_000)).longClick()
            withTimeout(5_000) { a.library.ui.first { it.selected.size == 1 } }
            assertNoButton("selection started")
            device.pressBack() // ends the selection
            shown(2, "selection ended")
        }
    }

    @Test fun a_deleted_last_book_gives_way_to_the_most_recent_that_exists(): Unit = runBlocking {
        val now = graph.clock.now()
        markRead(4, now - 2); markRead(6, now - 1); markRead(8, now)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            launched(sc)
            shown(8, "the last book")
            awayAndBack(sc) { assertTrue(file(8).delete()) }
            shown(6, "the last book's file is gone")
            awayAndBack(sc) { assertTrue(file(6).delete()); assertTrue(file(4).delete()) }
            assertNoButton("no read book is left")
        }
    }

    @Test fun the_last_row_stays_clear_of_the_button(): Unit = runBlocking {
        markRead(2, graph.clock.now())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val a = launched(sc)
            sc.onActivity { it.library.openView(LibraryView.Folder(dir.path)) }
            for (layout in LibraryLayout.entries) {
                sc.onActivity { it.library.setLayout(layout) }
                withTimeout(5_000) { a.library.ui.first { it.layout == layout && it.books.size == 14 } }
                val x = device.displayWidth / 3
                repeat(6) { device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 4, 15) }
                assertTrue("$layout: at the end of the list", waitFresh(5_000) { device.hasObject(By.text(title(14))) })
                assertNotNull("$layout: the fling is over", device.settled(By.text(title(14)), 5_000))
                val top = shown(2, "$layout").visibleBounds.top
                val rows = waitFresh(0) { true }.let { device.findObjects(By.text("Test Author")).map { it.visibleBounds.bottom } }
                assertTrue("$layout: rows end at ${rows.max()}, the button starts at $top", rows.max() <= top)
                device.swipe(x, device.displayHeight / 4, x, device.displayHeight * 3 / 4, 15) // back up, for the next layout
            }
        }
    }
}
