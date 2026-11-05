package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-079 (F-031): scroll mode is stable under real gestures. While the finger scrolls down (drags and flings), the reading
 * position (current section, offset in it) only moves forward: nothing snaps it back to a saved or jumped-to position,
 * inserting a section above does not move the text, and the next section is there when the current one ends. The page
 * label never goes down. Replays the pattern of the user's recordings (front matter of a technical book with images,
 * a TOC jump then scrolling) on two kinds of book.
 */
@RunWith(AndroidJUnit4::class)
class ScrollStabilityTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun book(path: String) = File(path).also { check(it.isFile) { "corpus not pushed: $it" } }

    /** Opens [path] and waits until the controller shows that book (title [title]) with its own contents list. */
    private suspend fun ReaderHarness.openBook(path: String, title: String) {
        open(book(path))
        kotlinx.coroutines.withTimeout(15_000) { a.controller.state.first { it.title.contains(title, ignoreCase = true) && it.toc.isNotEmpty() } }
        settle()
    }

    /** Samples (current section, scroll offset in it) on every frame into `window.__pos` until [stop]. */
    private suspend fun ReaderHarness.startSampling() = sc.js("""window.__pos = []; window.__posOn = true;
        (function loop() { const st = window.__readerTest.slots(); if (st) {
            const cur = st.slots.find(s => s.index === st.current);
            if (cur) window.__pos.push([st.current, Math.round(st.scrollTop - cur.top)]) }
          if (window.__posOn) requestAnimationFrame(loop) })(); true""")

    private suspend fun ReaderHarness.stopSampling(): List<Pair<Int, Int>> {
        sc.js("window.__posOn = false; true")
        val a = JSONArray(JSONTokener(sc.js("JSON.stringify(window.__pos)")).nextValue() as String)
        return List(a.length()) { a.getJSONArray(it).let { p -> p.getInt(0) to p.getInt(1) } }
    }

    private fun drag() = dev.swipe(dev.displayWidth / 2, dev.displayHeight * 4 / 5, dev.displayWidth / 2, dev.displayHeight / 5, 40)
    private fun fling() = dev.swipe(dev.displayWidth / 2, dev.displayHeight * 4 / 5, dev.displayWidth / 2, dev.displayHeight / 5, 4)

    /**
     * Scrolls down with [gestures] drags and flings, sampling every frame; asserts the position never moves back by more
     * than 2 px (a section switch counts as forward when the index grows) and the page label never decreases.
     */
    private suspend fun ReaderHarness.scrollDownSteadily(gestures: Int, what: String): List<Pair<Int, Int>> {
        val labels = mutableListOf<Int>()
        startSampling()
        repeat(gestures) { k ->
            if (k % 3 == 2) drag() else fling()
            delay(900) // a reader's pace: how far the 15 gestures get, and that they overlap the run-outs, is the scenario
            a.controller.page.value.page?.let { labels += it }
        }
        delay(600) // the last run-out, sampled as well
        val pos = stopSampling()
        assertTrue("$what: sampled ${pos.size} frames", pos.size > 20)
        for (k in 1 until pos.size) {
            val (i0, o0) = pos[k - 1]; val (i1, o1) = pos[k]
            val back = i1 < i0 || (i1 == i0 && o1 < o0 - 2)
            assertTrue("$what: position moved back at frame $k: ${pos[k - 1]} -> ${pos[k]} (around ${pos.subList(maxOf(0, k - 5), minOf(pos.size, k + 5))})", !back)
        }
        for (k in 1 until labels.size) assertTrue("$what: page label went down: $labels", labels[k] >= labels[k - 1])
        return pos
    }

    private fun ReaderHarness.scrolled() = sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = Flow.SCROLLED) } }

    private fun run(path: String, title: String, jump: String?) = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.openBook(path, title)
                h.scrolled(); h.still()
                // "the start": the first contents entry (resuming would start wherever an earlier run left the book)
                val start = h.tocJump(jump ?: h.a.controller.state.value.toc.first().label).sectionIndex
                h.still()
                val pos = h.scrollDownSteadily(15, "${File(path).name} from ${jump ?: "the start"}")
                assertTrue("went on into later sections: from $start to ${pos.last().first}; samples ${pos.first()}..${pos.last()}, max offset ${pos.maxOf { it.second }}; slots ${h.sc.js("JSON.stringify(window.__readerTest.slots())")}",
                    pos.last().first >= start + 2)
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.a.settingsStored(); h.stop() }
        }
    }

    /** A technical book's front matter (cover, title, copyright, contents, preface), with images. */
    @Test fun technical_book_front_matter_scrolls_forward_only() = run("/sdcard/Books/epub3-samples/accessible_epub_3.epub", "Accessible", null)

    /** After a TOC jump, scrolling goes on from there and is not pulled back to the jump target. */
    @Test fun scrolling_after_a_toc_jump_is_not_pulled_back() = run("/sdcard/Books/epub3-samples/accessible_epub_3.epub", "Accessible", "Preface")

    /** Short chapters with images. */
    @Test fun short_illustrated_chapters_scroll_forward_only() = run("/sdcard/Books/gutenberg/alice-epub3-images.epub", "Alice", null)

    /** Long chapters without images. */
    @Test fun long_chapters_scroll_forward_only() = run("/sdcard/Books/gutenberg/war-and-peace-epub3.epub", "War and Peace", "CHAPTER I")

    /** Where the reader is: current section, offset in it, first visible block of text. */
    private suspend fun ReaderHarness.where(): Triple<Int, Int, String?> {
        val st = org.json.JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.slots())")).nextValue() as String)
        val slots = st.getJSONArray("slots")
        val cur = (0 until slots.length()).map { slots.getJSONObject(it) }.first { it.optInt("index", -1) == st.getInt("current") }
        val block = JSONTokener(sc.js("window.__readerTest.firstVisibleBlock()")).nextValue() as? String
        return Triple(st.getInt("current"), Math.round(st.getDouble("scrollTop") - cur.getDouble("top")).toInt(), block)
    }

    /**
     * A Contents jump as the user makes it (the panel's selection, no seek id): the reader lands on the entry's section
     * and stays there for 3 s (same section, same offset, same text), and the page label is that position's.
     */
    private suspend fun ReaderHarness.contentsJump(label: String, what: String) {
        val item = a.controller.state.value.toc.first { it.label.contains(label, ignoreCase = true) }
        drain()
        sc.onActivity { it.controller.onTocSelected(item) }
        val landed = relocate(10_000) { it.sectionIndex == item.index } // not a late report of the scrolling before it
        still()
        val first = where()
        assertEquals("$what: landed in the entry's section (relocate ${landed.sectionIndex})", landed.sectionIndex, first.first)
        val page = a.controller.page.value.page
        delay(3_000) // the check that it stays there: fixed
        assertEquals("$what: still there 3 s later", first, where())
        assertEquals("$what: the page label stays", page, a.controller.page.value.page)
    }

    /** C-079/C-080: Contents jumps in scroll mode, far back and forward, after reading on (the Manager's Path video). */
    @Test fun contents_jumps_in_scroll_mode_land_and_stay(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.openBook("/sdcard/Books/epub3-samples/accessible_epub_3.epub", "Accessible")
                h.scrolled(); h.still()
                h.contentsJump("2. Building", "forward to chapter 2")
                repeat(3) { fling(); delay(900) }; h.still() // a jump during the run-out is another case: see the commit
                h.contentsJump("Preface", "back to the Preface")
                h.contentsJump("Acknowledgments", "forward to Acknowledgments")
                h.contentsJump("1. Introduction", "to chapter 1")
                repeat(3) { fling(); delay(900) }; h.still() // a jump during the run-out is another case: see the commit
                h.contentsJump("Preface", "back to the Preface again")
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.a.settingsStored(); h.stop() }
        }
    }

    @Test fun contents_jumps_in_scroll_mode_in_a_long_book(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.openBook("/sdcard/Books/gutenberg/war-and-peace-epub3.epub", "War and Peace")
                h.scrolled(); h.still()
                h.contentsJump("BOOK THREE", "forward far")
                repeat(3) { fling(); delay(900) }; h.still() // a jump during the run-out is another case: see the commit
                h.contentsJump("BOOK ONE", "far back")
                h.contentsJump("BOOK TWO", "forward")
                h.contentsJump("BOOK ONE", "back again")
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.a.settingsStored(); h.stop() }
        }
    }

    /** C-080 (review I1): a jump to the book's last contents entry (a short final section) lands there and stays. */
    @Test fun contents_jump_to_the_last_entry_lands_and_stays(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.openBook("/sdcard/Books/epub3-samples/accessible_epub_3.epub", "Accessible")
                h.scrolled(); h.still()
                h.contentsJump("1. Introduction", "start")
                h.contentsJump("About the Book", "to the last entry (the end of the last chapter)")
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.a.settingsStored(); h.stop() }
        }
    }
}
