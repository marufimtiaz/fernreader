package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.ui.Copy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F-041: add with confirmation and a page marker, list with chapter and page, jump, remove. */
@RunWith(AndroidJUnit4::class)
class BookmarksTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    private fun showControls(h: ReaderHarness) {
        dev.click(dev.displayWidth / 2, dev.displayHeight / 2)
        assertTrue(dev.wait(Until.hasObject(By.desc(Copy.contents)), 3_000))
    }

    @Test fun add_mark_list_jump_and_remove(): Unit = runBlocking {
        val f = TestBooks.write(inst.targetContext, "bookmarks-${System.nanoTime()}.epub", TestBooks.epub(id = "urn:uuid:bm-${System.nanoTime()}", chapters = 3, paragraphs = 60))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            var here = h.open(f)
            repeat(3) { val from = here; h.send(ToWeb.Next()); here = h.relocate { it.cfi != from.cfi } }
            h.settle()
            val c = h.a.controller
            assertFalse(c.state.value.bookmarked)

            // Add: confirmation, filled button and the marker on the page (visible with the controls hidden too)
            showControls(h)
            dev.findObject(By.desc(Copy.addBookmark)).click()
            assertTrue("confirmation", dev.wait(Until.hasObject(By.text(Copy.bookmarkAdded)), 3_000))
            withTimeout(5_000) { c.state.first { it.bookmarked && it.bookmarks.size == 1 } }
            val bookmark = c.state.value.bookmarks.single()
            assertEquals(here.cfi, bookmark.cfi)
            assertEquals(here.sectionIndex, bookmark.spineIndex)
            assertTrue("chapter label '${bookmark.chapter}'", bookmark.chapter.startsWith("Chapter"))
            dev.click(dev.displayWidth / 2, dev.displayHeight / 2) // hide the controls
            withTimeout(3_000) { c.state.first { !it.chromeVisible } }
            assertTrue("marker without controls", dev.wait(Until.hasObject(By.desc(Copy.bookmarkedPage)), 3_000))

            // Another page: no marker. Back again: marker returns (the bookmark's position is on this page).
            val marked = here
            h.send(ToWeb.Next()); h.relocate { it.cfi != marked.cfi }
            withTimeout(5_000) { c.state.first { !it.bookmarked } }
            assertTrue(dev.wait(Until.gone(By.desc(Copy.bookmarkedPage)), 3_000))

            // The list: one row with chapter and a page (or percentage), and the date
            showControls(h)
            dev.findObject(By.desc(Copy.contents)).click()
            assertTrue(dev.wait(Until.hasObject(By.text(Copy.bookmarksTab)), 3_000))
            dev.findObject(By.text(Copy.bookmarksTab)).click()
            val row = dev.wait(Until.hasObject(By.textContains(" · ")), 10_000)
            if (!row) { val o = java.io.ByteArrayOutputStream(); dev.dumpWindowHierarchy(o); android.util.Log.i("BMT", Regex("(content-desc|text)=\"([^\"]+)\"").findAll(o.toString()).joinToString(" | ") { it.value }.take(1500)) }
            assertTrue("row", row)
            assertTrue(dev.hasObject(By.text(bookmark.chapter)))
            assertEquals(1, dev.findObjects(By.desc(Copy.removeBookmark)).size)

            // Tap = jump back to the same page, with the marker; the panel closes
            dev.findObject(By.textContains(" · ")).click()
            val landed = h.relocate { it.cfi == marked.cfi }
            assertEquals(marked.cfi, landed.cfi)
            withTimeout(5_000) { c.state.first { !it.tocVisible && it.bookmarked } }
            assertTrue(dev.wait(Until.hasObject(By.desc(Copy.bookmarkedPage)), 3_000))

            // Remove from the list: empty state, no marker
            showControls(h)
            dev.findObject(By.desc(Copy.contents)).click()
            assertTrue(dev.wait(Until.hasObject(By.text(Copy.bookmarksTab)), 3_000))
            dev.findObject(By.text(Copy.bookmarksTab)).click()
            assertTrue(dev.wait(Until.hasObject(By.desc(Copy.removeBookmark)), 3_000))
            dev.findObject(By.desc(Copy.removeBookmark)).click()
            // (UiAutomator's view of a changed Compose node can lag, so what disappears is checked in the state)
            withTimeout(5_000) { c.state.first { it.bookmarks.isEmpty() && !it.bookmarked } }
            assertEquals(Notice.BookmarkRemoved, c.state.value.notice)
            dev.findObject(By.text(Copy.close)).click()
            assertTrue(dev.wait(Until.gone(By.desc(Copy.bookmarkedPage)), 3_000))

            // Toggle off again from the button: add, then the filled button removes it
            showControls(h)
            val at = dev.findObject(By.desc(Copy.addBookmark)).visibleCenter
            dev.click(at.x, at.y)
            withTimeout(5_000) { c.state.first { it.bookmarked } }
            dev.click(at.x, at.y) // the same button, filled now
            withTimeout(5_000) { c.state.first { it.notice == Notice.BookmarkRemoved } }
            withTimeout(5_000) { c.state.first { !it.bookmarked && it.bookmarks.isEmpty() } }
            h.stop()
        }
    }

    /** I1 (review): a book without a table of contents still reaches its bookmarks; the panel has a title row. */
    @Test fun a_book_without_a_toc_lists_its_bookmarks(): Unit = runBlocking {
        val title = "No Contents Book"
        val f = TestBooks.write(inst.targetContext, "notoc-${System.nanoTime()}.epub", TestBooks.epub(id = "urn:uuid:notoc-${System.nanoTime()}", title = title, chapters = 2, paragraphs = 30, noToc = true))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            h.open(f, withToc = false)
            val c = h.a.controller
            assertEquals(emptyList<Any>(), c.state.value.toc)
            showControls(h)
            dev.findObject(By.desc(Copy.addBookmark)).click()
            withTimeout(5_000) { c.state.first { it.bookmarks.size == 1 } }
            dev.findObject(By.desc(Copy.contents)).click() // the controls are still shown
            assertTrue("panel opens", dev.wait(Until.hasObject(By.text(Copy.bookmarksTab)), 3_000))
            assertTrue("empty contents tab says so", dev.wait(Until.hasObject(By.text(Copy.noContents)), 3_000))
            assertTrue("title row names the book", dev.hasObject(By.text(title)))
            assertTrue(dev.hasObject(By.text(Copy.close)))
            dev.findObject(By.text(Copy.bookmarksTab)).click()
            assertTrue("bookmark row", dev.wait(Until.hasObject(By.desc(Copy.removeBookmark)), 5_000))
            dev.findObject(By.desc(Copy.removeBookmark)).click()
            withTimeout(5_000) { c.state.first { it.bookmarks.isEmpty() } }
            dev.findObject(By.text(Copy.close)).click()
            withTimeout(3_000) { c.state.first { !it.tocVisible } }
            h.stop()
        }
    }
}
