package app.epubreader.library

import android.os.Environment
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SettingsStore
import app.epubreader.core.library.ScanScope
import app.epubreader.waitFresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-075 (F-012): a book copied into a new folder while the app is in the background shows in the library, its folder
 * in the folder list and an open folder view within 2 s of coming back, with no manual rescan; opening a folder view
 * scans too. C-076: the new book is on top of the "recently read" order, in all books and in its folder.
 * The copies are made by the shell through /sdcard, as a file manager does (FUSE: no file events reach the app).
 */
@RunWith(AndroidJUnit4::class)
class ScanOnReturnTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val sd = Environment.getExternalStorageDirectory()
    /** Sources for the shell's copies, outside shared storage (the shell cannot read files the app made there). */
    private val src = "/data/local/tmp/c075-src"
    private val newDir = File(sd, "Documents/C075Return")
    private val folder = File(sd, "Documents/C076Folder")

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).use { p ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(p).readBytes().decodeToString()
    }
    /** Writes a book where only the shell can read it (through the shell's stdin); returns its path. */
    private fun source(name: String, title: String): String {
        val path = "$src/$name.epub"
        shell("mkdir -p $src")
        // executeShellCommand runs no shell (no quotes, no redirection): dd writes its stdin to the file
        val (out, inp) = inst.uiAutomation.executeShellCommandRw("dd of=$path").let { it[0] to it[1] }
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(inp).use { it.write(TestBooks.epub(id = "urn:uuid:c075-$name", title = title)) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() } // the shell is done
        return path
    }
    /** The shell's copy, checked: it must not fail silently (a missing book would look like a missed scan). */
    private fun copy(from: String, to: File) {
        val out = shell("mkdir -p ${to.parent}") + shell("cp $from ${to.path}")
        check(to.isFile) { "copy failed: $from → $to: $out" }
    }

    @Before fun setUp() {
        shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")
        listOf(newDir, folder).forEach { it.deleteRecursively() }
        assumeTrue("needs the shell's stdin (API 31)", android.os.Build.VERSION.SDK_INT >= 31)
        runBlocking {
            graph.scanner.scan(ScanScope.Everywhere) // the test books of an earlier run are gone
            // the default order, which other tests may have changed (BackupRestoreTest leaves "author")
            graph.settings.put(SettingsStore.LIBRARY_SORT, LibrarySort.RECENT.name)
        }
    }

    @After fun tearDown() {
        listOf(newDir, folder).forEach { it.deleteRecursively() }
        shell("rm -rf $src")
        runBlocking { graph.scanner.scan(ScanScope.Everywhere) }
    }

    private suspend fun launched(sc: ActivityScenario<MainActivity>): MainActivity {
        sc.onActivity { a = it }
        withTimeout(15_000) { a.library.ui.first { it.loaded && !it.scan.running && it.scan.lastComplete != null } }
        return a
    }

    /**
     * Home, [whileAway] (the shell's copy), then back through the launcher's intent (the task comes to the front, as
     * from recents). MAIN/LAUNCHER as the scenario's own intent: the activity takes the new intent, and with another
     * one the scenario would lose track of it.
     */
    private suspend fun awayAndBack(sc: ActivityScenario<MainActivity>, whileAway: () -> Unit): Long {
        device.pressHome()
        withTimeout(5_000) { while (sc.state != Lifecycle.State.CREATED) delay(20) }
        whileAway()
        val t0 = SystemClock.uptimeMillis()
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${ctx.packageName}/app.epubreader.MainActivity")
        return t0
    }

    private lateinit var a: MainActivity
    /** [text] on screen within [limitMs] of [t0] (polled with a fresh accessibility cache, [waitFresh]). */
    private fun shownWithin(text: String, t0: Long, limitMs: Long = 2_000): Long {
        val found = waitFresh(t0 + limitMs + 3_000 - SystemClock.uptimeMillis()) { device.hasObject(By.text(text)) }
        val ms = SystemClock.uptimeMillis() - t0
        // the state is read after the wait (a message built before it showed the list from before the scan)
        assertTrue("not shown: $text; ${a.library.ui.value.view}, top ${a.library.ui.value.books.take(3).map { it.title }}, ${a.library.ui.value.scan}", found)
        return ms.also { assertTrue("$text shown after $it ms (limit $limitMs)", it <= limitMs) }
    }

    @Test fun book_copied_into_a_new_folder_while_away_shows_on_return(): Unit = runBlocking {
        val book = source("ret", "Zq Return C075")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val a = launched(sc)
            val t0 = awayAndBack(sc) { copy(book, File(newDir, "Inner/zq-return.epub")) }
            val ms = shownWithin("Zq Return C075", t0)
            android.util.Log.i("EPR", "C-075 return: book shown after $ms ms")
            assertEquals("on top of all books (C-076)", "Zq Return C075", a.library.ui.value.books.first().title)
            assertTrue("no scan progress left", !a.library.ui.value.scan.showsProgress)
            // its folder is in the folder list (the data the drawer's folder section shows, C-074; the list may be
            // longer than the screen, so its rows are checked, not the screen)
            sc.onActivity { it.library.openView(LibraryView.Folders) }
            val tree = withTimeout(5_000) { a.library.ui.first { u -> u.folderTree.any { it.path == "${newDir.path}/Inner" } } }.folderTree
            assertEquals(1, tree.single { it.path == "${newDir.path}/Inner" }.direct)
            assertEquals(1, tree.single { it.path == newDir.path }.total)
        }
    }

    @Test fun open_folder_view_gets_a_new_book_on_top(): Unit = runBlocking {
        folder.mkdirs()
        File(folder, "a.epub").writeBytes(TestBooks.epub(id = "urn:uuid:c076-a", title = "Zq Older A"))
        File(folder, "b.epub").writeBytes(TestBooks.epub(id = "urn:uuid:c076-b", title = "Zq Older B"))
        graph.scanner.scan(ScanScope.Folder(folder.path))
        val read = graph.library.library().first().single { it.title == "Zq Older B" }
        graph.db.libraryDao().touchOpened(read.id, graph.clock.now()) // read after both were added
        val book = source("new", "Zq Newest C076")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val a = launched(sc)
            sc.onActivity { it.library.openView(LibraryView.Folder(folder.path)) }
            val before = withTimeoutOrNull(5_000) { a.library.ui.first { it.books.map { b -> b.title } == listOf("Zq Older B", "Zq Older A") } }
            assertTrue("folder view: ${a.library.ui.value.books.map { it.title }}", before != null)
            val t0 = awayAndBack(sc) { copy(book, File(folder, "newest.epub")) }
            shownWithin("Zq Newest C076", t0)
            assertEquals(listOf("Zq Newest C076", "Zq Older B", "Zq Older A"), a.library.ui.value.books.map { it.title })
            // and on screen: the new book is the first cover (top row, leading edge)
            val first = device.findObject(By.text("Zq Newest C076")).visibleBounds
            val second = device.findObject(By.text("Zq Older B")).visibleBounds
            assertTrue("$first before $second", first.top < second.top || (first.top == second.top && first.left < second.left))
        }
    }

    @Test fun opening_a_folder_view_scans(): Unit = runBlocking {
        folder.mkdirs()
        File(folder, "a.epub").writeBytes(TestBooks.epub(id = "urn:uuid:c076-a", title = "Zq Older A"))
        graph.scanner.scan(ScanScope.Folder(folder.path))
        val book = source("view", "Zq Seen On View")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val a = launched(sc)
            // The worst case, made certain: an automatic scan has just completed (in the full suite the app start scan
            // finished within 2 s of the view opening, and the view used to skip its scan then). The book arrives
            // right after it, while the app is in front (e.g. over USB): no event reaches the app.
            delay(1_000); withTimeout(10_000) { graph.scans.state.first { !it.running } } // the app start scan is done
            graph.scans.onForeground()
            // (a scan can be over before a collector sees it running, and an equal result is no new state: no wait on either)
            withTimeoutOrNull(2_000) { graph.scans.state.first { it.running } }
            withTimeout(10_000) { graph.scans.state.first { !it.running } }
            copy(book, File(folder, "view.epub"))
            val t0 = SystemClock.uptimeMillis()
            sc.onActivity { it.library.openView(LibraryView.Folder(folder.path)) }
            shownWithin("Zq Seen On View", t0)
            assertEquals("Zq Seen On View", a.library.ui.value.books.first().title)
        }
    }
}
