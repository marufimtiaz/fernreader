package app.fernreader.library

import android.graphics.Rect
import android.os.Environment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.SettingsStore
import app.fernreader.core.library.ScanScope
import app.fernreader.ui.Copy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-077 through the real screen, in a folder view of three test books: the search icon expands the bar into a field
 * that filters, Back collapses it and clears the query; the sort menu (current order checked) changes the order on
 * screen; the layout icon shows the layout it switches to and toggles grid/list; pull-to-refresh starts a manual rescan.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTopBarTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val graph get() = (ctx.applicationContext as EpubReaderApp).graph
    private val dir = File(Environment.getExternalStorageDirectory(), "Download/c077-bar-test")

    @Before fun setUp() {
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        dir.deleteRecursively(); dir.mkdirs()
        listOf("Cherry", "Apple", "Banana").forEach { t -> File(dir, "${t.lowercase()}.epub").writeBytes(TestBooks.epub(id = "urn:uuid:c077-$t", title = "Zb $t")) }
        runBlocking {
            graph.scanner.scan(ScanScope.Folder(dir.path))
            val cherry = graph.library.library().first().single { it.title == "Zb Cherry" }
            graph.db.libraryDao().touchOpened(cherry.id, graph.clock.now()) // "recently read": Cherry first
            graph.settings.put(SettingsStore.LIBRARY_SORT, LibrarySort.RECENT.name)
            graph.settings.put(SettingsStore.LIBRARY_LAYOUT, LibraryLayout.GRID.name)
        }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        runBlocking {
            graph.settings.put(SettingsStore.LIBRARY_SORT, LibrarySort.RECENT.name)
            graph.settings.put(SettingsStore.LIBRARY_LAYOUT, LibraryLayout.GRID.name)
            graph.scanner.scan(ScanScope.Folder(dir.parent!!)) // prune its rows (the deleted folder itself can't be listed)
        }
    }

    private fun obj(sel: BySelector, what: String, ms: Long = 5_000): UiObject2 = checkNotNull(device.wait(Until.findObject(sel), ms)) { "not shown: $what" }
    /** Reading order on screen (rows, then left to right), for the grid and the list. */
    private fun screenOrder(titles: List<String>): List<String> =
        titles.sortedWith(compareBy<String>({ t: String -> rect(t).top / 40 }, { t: String -> rect(t).left }))
    private fun rect(title: String): Rect = obj(By.text(title), title).visibleBounds

    @Test fun search_sort_layout_and_pull_to_refresh(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.library.ui.first { it.loaded } }
            sc.onActivity { it.library.openView(LibraryView.Folder(dir.path)) }
            withTimeout(5_000) { a.library.ui.first { it.books.size == 3 } }
            val all = listOf("Zb Apple", "Zb Banana", "Zb Cherry")
            all.forEach { obj(By.text(it), it) }
            assertFalse("no permanent search field", device.hasObject(By.clazz("android.widget.EditText")))
            assertFalse("no text-link row", device.hasObject(By.textStartsWith("Sort")))

            // search: the icon expands the field (focused), typing filters
            obj(By.desc(Copy.search), "search icon").click()
            val field = obj(By.clazz("android.widget.EditText").focused(true), "focused search field")
            field.text = "apple"
            assertTrue(device.wait(Until.gone(By.text("Zb Banana")), 5_000))
            assertTrue(device.hasObject(By.text("Zb Apple")))
            // Back collapses it and clears the query; the folder view stays
            device.pressBack() // the keyboard may take the first Back
            if (a.library.ui.value.searchOpen) device.pressBack()
            val u = withTimeout(5_000) { a.library.ui.first { !it.searchOpen } }
            assertEquals("", u.query); assertEquals(LibraryView.Folder(dir.path), u.view)
            assertTrue(device.wait(Until.gone(By.clazz("android.widget.EditText")), 5_000))
            all.forEach { obj(By.text(it), it) }
            // the close icon does the same
            obj(By.desc(Copy.search), "search icon").click()
            obj(By.clazz("android.widget.EditText"), "search field").text = "cherry"
            assertTrue(device.wait(Until.gone(By.text("Zb Apple")), 5_000))
            obj(By.desc(Copy.closeSearch), "close search").click()
            withTimeout(5_000) { a.library.ui.first { !it.searchOpen && it.query.isEmpty() && it.books.size == 3 } }

            // sort: the menu has the current order checked; choosing Title reorders the screen
            assertEquals(listOf("Zb Cherry"), screenOrder(all).take(1))
            obj(By.desc(Copy.sortBy), "sort icon").click()
            // the menu item is the checked node; its label is a child text node
            obj(By.checked(true).hasChild(By.text(Copy.sortName(LibrarySort.RECENT))), "current order checked")
            obj(By.text(Copy.sortName(LibrarySort.TITLE)), "Title").click()
            withTimeout(5_000) { a.library.ui.first { it.sort == LibrarySort.TITLE } }
            assertTrue(device.wait(Until.gone(By.text(Copy.sortName(LibrarySort.ADDED))), 5_000)) // the menu closed
            assertEquals(all, screenOrder(all))
            assertEquals(LibrarySort.TITLE.name, graph.settings.get(SettingsStore.LIBRARY_SORT))

            // layout: the icon names the layout it switches to
            obj(By.desc(Copy.showAsList), "list icon").click()
            withTimeout(5_000) { a.library.ui.first { it.layout == LibraryLayout.LIST } }
            obj(By.desc(Copy.showAsGrid), "grid icon")
            assertFalse(device.hasObject(By.desc(Copy.showAsList)))
            assertTrue("list rows are stacked", rect("Zb Apple").bottom <= rect("Zb Banana").top)
            assertEquals(all, screenOrder(all))
            obj(By.desc(Copy.showAsGrid), "grid icon").click()
            withTimeout(5_000) { a.library.ui.first { it.layout == LibraryLayout.GRID } }
            obj(By.desc(Copy.showAsList), "list icon")

            // pull-to-refresh: a manual (not quiet) rescan runs
            // (a manual scan can take 12 ms: its trace is the finished state, [ScanState.quiet] stays until the next scan)
            val before = withTimeout(15_000) { graph.scans.state.first { !it.running } }.lastComplete
            val r = rect("Zb Apple")
            device.swipe(device.displayWidth / 2, r.top, device.displayWidth / 2, r.top + device.displayHeight / 2, 40)
            withTimeout(10_000) { graph.scans.state.first { !it.running && !it.quiet && it.lastComplete !== before } }
        }
    }
}
