package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ScrollChapters
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.search.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.math.abs

/**
 * C-080 (with C-079): every deliberate navigation lands on its target and stays there, in both reading modes (F-030
 * paged, F-031 scrolled). Each test drives one kind of navigation through the entry point the UI uses:
 *  - Contents jump (`onTocSelected`), far forward and far back;
 *  - search hit (F-050: `searchFor`, then `searcher.select`);
 *  - internal link and Back (F-042, footnotes-links.epub);
 *  - bookmark row (F-041: `onBookmarkSelected`);
 *  - highlight row (F-061: `onAnnotationSelected`);
 *  - slider seek (`seek`);
 *  - resume after close and reopen (F-037).
 *
 * The pass criterion is the same for all ([assertLandsAndHolds]): right after the landing the target is on screen (web
 * probes in `window.__readerTest`), the relocate's section and the shown section are the target's, and 3 s later the
 * target is still on screen with the same section and the same first visible block, and no relocate left the section.
 *
 * Each test starts with a Contents jump to a known place (never the resume position an earlier test left) and removes
 * the bookmarks and highlights it made.
 */
@RunWith(Parameterized::class)
class NavigationInBothFlowsTest(private val flow: Flow, private val chapters: ScrollChapters) {
    companion object {
        /** C-085: scroll mode once with Continuous chapters (C-079) and once with One at a time; jumps behave the same. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun flows(): List<Array<Any>> = listOf(
            arrayOf(Flow.PAGED, ScrollChapters.CONTINUOUS),
            arrayOf(Flow.SCROLLED, ScrollChapters.CONTINUOUS),
            arrayOf(Flow.SCROLLED, ScrollChapters.ONE_AT_A_TIME),
        )

        private const val WAR_AND_PEACE = "War and Peace"
        private const val FOOTNOTES = "Footnotes and Links"
        private const val LINK = "the target in chapter two"
        private const val LINK_TARGET = "This is the link target paragraph."
        private const val NEEDLE = "Borodinó"
    }

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph
    private val madeBookmarks = mutableListOf<String>()
    private val madeAnnotations = mutableListOf<String>()

    private fun footnotesBook(): File {
        warAndPeace() // grants all-files access
        return File("/sdcard/Books/generated/footnotes-links.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
    }

    // ---- probes ----

    private suspend fun ActivityScenario<MainActivity>.jsValue(expr: String): Any? =
        JSONTokener(js("JSON.stringify($expr)")).nextValue().let { if (it is String) JSONTokener(it).nextValue() else null }

    private suspend fun ActivityScenario<MainActivity>.str(expr: String): String? = jsValue(expr) as? String
    private suspend fun ActivityScenario<MainActivity>.blockVisible(text: String) = jsValue("window.__readerTest.blockVisible(${JSONObject.quote(text)})") == true
    private suspend fun ActivityScenario<MainActivity>.firstVisibleBlock() = str("window.__readerTest.firstVisibleBlock()")

    /** A host-viewport rectangle probe result (`x, y, w, h, vw, vh`) fully on screen. */
    private fun JSONObject.onScreen() = getDouble("x") >= 0 && getDouble("y") >= 0 &&
        getDouble("x") + getDouble("w") <= getDouble("vw") && getDouble("y") + getDouble("h") <= getDouble("vh")

    /** Where the reader is: the last relocate's section, the view's current section and the first visible block. */
    private data class Spot(val relocated: Int, val shown: Int, val block: String?)

    private suspend fun ReaderHarness.spot(): Spot {
        drain()
        val page = sc.jsValue("window.__readerTest.page()") as? JSONObject
        return Spot(at!!.sectionIndex, page?.optInt("index", -1) ?: -1, sc.firstVisibleBlock())
    }

    // ---- the shared criterion ----

    /**
     * C-080: [check] (null when the target is on screen, else what is wrong) passes right after the landing (polled for
     * at most 5 s while the landing finishes drawing), the relocate and the view are in [section]; 3 s later [check]
     * still passes, the section and the first visible block are unchanged, and no relocate in between left [section].
     */
    private suspend fun ReaderHarness.assertLandsAndHolds(what: String, section: Int, check: suspend () -> String?) = coroutineScope {
        val label = "$what ($flow $chapters)"
        var problem: String? = "not checked"
        withTimeoutOrNull(5_000) { while (true) { problem = check(); if (problem == null) break; delay(100) } }
        val first = spot()
        assertNull("$label: target not on screen after the landing: $problem (at $first)", problem)
        assertEquals("$label: the relocate's section is the target's ($first)", section, first.relocated)
        assertEquals("$label: the shown section is the target's ($first)", section, first.shown)
        assertNotNull("$label: a first visible block ($first)", first.block)

        val moves = mutableListOf<FromWeb.Relocate>()
        val watch = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { moves += it } }
        delay(3_000) // the check that it stays there: fixed
        watch.cancel()
        val later = check()
        val second = spot()
        assertNull("$label: target left the screen within 3 s: $later (first $first, now $second)", later)
        assertEquals("$label: same section and first visible block 3 s later", first, second)
        assertEquals("$label: relocates during the 3 s left the section",
            emptyList<String>(), moves.filter { it.sectionIndex != section }.map { "${it.sectionIndex} ${it.cfi}" })
    }

    /** Waits for the relocate [ok] after a navigation, failing with where the reader is instead of a bare timeout. */
    private suspend fun ReaderHarness.landing(what: String, ms: Long = 15_000, ok: (FromWeb.Relocate) -> Boolean): FromWeb.Relocate =
        try { relocate(ms, ok) } catch (e: TimeoutCancellationException) {
            fail("$what ($flow): no relocate to the target within $ms ms (last: section ${at?.sectionIndex} ${at?.cfi})"); error("unreachable")
        }

    // ---- setup ----

    private fun run(book: File, title: String, block: suspend CoroutineScope.(ReaderHarness) -> Unit): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = flow, scrollChapters = chapters) } }
                h.openBook(book, title)
                block(h)
            } finally {
                for (id in madeAnnotations) runCatching { graph.annotations.remove(id) }
                for (id in madeBookmarks) runCatching { graph.bookmarks.remove(id) }
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    /** Opens [f] and waits until the controller shows that book (title [title]) with its own contents list. */
    private suspend fun ReaderHarness.openBook(f: File, title: String) {
        open(f)
        withTimeout(15_000) { a.controller.state.first { it.title.contains(title, ignoreCase = true) && it.toc.isNotEmpty() } }
        quiet() // the flow change and the first layout
    }

    /** War and Peace's BOOK entries (far apart), or every usable entry if the labels differ. */
    private fun ReaderHarness.places(): List<TocItem> {
        val usable = a.controller.state.value.toc.filter { !it.missing && it.index >= 0 }
        val books = usable.filter { it.label.trim().startsWith("BOOK ", ignoreCase = true) }
        return (if (books.size >= 4) books else usable).also { assertTrue("contents entries: ${it.map { e -> e.label }}", it.size >= 4) }
    }

    private fun ReaderHarness.entry(label: String) = a.controller.state.value.toc.first { it.label.contains(label, ignoreCase = true) }

    /** The text a Contents entry's target starts with (its heading; the part before a colon, "BOOK ONE" of "BOOK ONE: 1805"). */
    private fun TocItem.heading() = label.substringBefore(':').trim()

    /** A Contents jump as the panel makes it; returns the relocate into the entry's section. */
    private suspend fun ReaderHarness.contentsJump(item: TocItem, what: String): FromWeb.Relocate {
        drain()
        sc.onActivity { it.controller.showToc(); it.controller.onTocSelected(item) }
        val r = landing("$what: Contents '${item.label}'") { it.sectionIndex == item.index }
        withTimeout(3_000) { a.controller.state.first { !it.tocVisible } }
        return r
    }

    /** Goes to a known place (a Contents jump) and waits until it is quiet there. */
    private suspend fun ReaderHarness.startAt(item: TocItem): FromWeb.Relocate {
        contentsJump(item, "start")
        return quiet()
    }

    private suspend fun bookId(f: File): String = withTimeout(10_000) {
        var id: String? = null
        while (id == null) { id = graph.library.knownBookId(SourceRef.File(f.path)); if (id == null) delay(100) }
        id
    }

    // ---- 1. Contents ----

    @Test fun contents_jump_far_forward_and_far_back_lands_and_holds() = run(warAndPeace(), WAR_AND_PEACE) { h ->
        val p = h.places()
        h.startAt(p.first())
        for ((item, what) in listOf(p[p.size - 2] to "Contents far forward", p[1] to "Contents far back")) {
            h.contentsJump(item, what)
            h.assertLandsAndHolds("$what to '${item.label}'", item.index) {
                if (h.sc.blockVisible(item.heading())) null else "heading '${item.heading()}' not visible (first block ${h.sc.firstVisibleBlock()})"
            }
        }
    }

    // ---- 2. Search ----

    private suspend fun ReaderHarness.searchDone(text: String): List<SearchResult> {
        withTimeout(60_000) { graph.search.progress(bookId(warAndPeace())).first { it.complete } }
        sc.onActivity { it.controller.searchFor(text) }
        return withTimeout(30_000) { a.controller.searcher.state.first { it.submitted == text && it.done } }.results
    }

    private suspend fun ReaderHarness.searchHitOnScreen(section: Int): String? {
        val cfi = sc.str("window.__readerTest.searchHighlight()") ?: return "no search highlight"
        val text = sc.str("window.__readerTest.cfiText(${JSONObject.quote(cfi)})") ?: return "the hit's range $cfi is not loaded"
        if (!text.equals(NEEDLE, ignoreCase = true)) return "the highlight covers '$text'"
        if (sc.js("window.__readerTest.overlayCount($section)").toIntOrNull()?.let { it > 0 } != true) return "no outline drawn in section $section"
        val r = sc.jsValue("window.__readerTest.wordRect(${JSONObject.quote(text)})") as? JSONObject
        return if (r != null && r.onScreen()) null else "'$text' is not on screen"
    }

    @Test fun search_hit_lands_and_holds() = run(warAndPeace(), WAR_AND_PEACE) { h ->
        val p = h.places()
        h.startAt(p.first())
        val results = h.searchDone(NEEDLE)
        assertTrue("hits for $NEEDLE: ${results.size}", results.size > 2)
        suspend fun select(r: SearchResult, what: String) {
            h.drain()
            h.sc.onActivity { it.controller.searcher.select(r) }
            h.landing(what) { it.sectionIndex == r.hit.spineIndex }
            h.assertLandsAndHolds(what, r.hit.spineIndex) { h.searchHitOnScreen(r.hit.spineIndex) }
        }
        select(results.first(), "search hit forward (first hit)")
        h.startAt(p[p.size - 2])
        select(results[results.size / 2], "search hit back (middle hit)")
    }

    // ---- 3. Link and Back ----

    @Test fun link_and_back_land_and_hold() = run(footnotesBook(), FOOTNOTES) { h ->
        val start = h.entry("Notes Test")
        h.startAt(start)
        // Bring the link fully on screen (page turns, or screens in scroll mode), so Back has a visible origin.
        var onScreen = false
        repeat(15) {
            if (onScreen) return@repeat
            onScreen = (h.sc.jsValue("window.__readerTest.linkRect(${JSONObject.quote(LINK)})") as? JSONObject)?.onScreen() == true
            if (!onScreen) { h.send(ToWeb.Next()); h.quiet() }
        }
        assertTrue("link '$LINK' never came fully on screen ($flow)", onScreen)
        val origin = h.spot()
        val originBlock = origin.block
        assertNotNull("origin block", originBlock)

        h.drain()
        assertEquals(true, h.sc.jsValue("window.__readerTest.clickLink(${JSONObject.quote(LINK)})"))
        h.landing("link") { it.sectionIndex == 1 }
        h.assertLandsAndHolds("link to chapter two", 1) {
            if (h.sc.blockVisible(LINK_TARGET)) null else "target paragraph not visible (first block ${h.sc.firstVisibleBlock()})"
        }
        assertEquals(1, h.sc.jsValue("window.__readerTest.historyDepth()"))

        h.drain()
        dev.pressBack()
        h.landing("Back") { it.sectionIndex == origin.relocated }
        h.assertLandsAndHolds("Back to the link's page", origin.relocated) {
            val r = h.sc.jsValue("window.__readerTest.linkRect(${JSONObject.quote(LINK)})") as? JSONObject
            when {
                r == null || !r.onScreen() -> "the link is not on screen ($r)"
                !h.sc.blockVisible(originBlock!!) -> "origin block '$originBlock' not visible (first block ${h.sc.firstVisibleBlock()})"
                else -> null
            }
        }
    }

    // ---- 4. Bookmark ----

    @Test fun bookmark_row_lands_and_holds() = run(warAndPeace(), WAR_AND_PEACE) { h ->
        val c = h.a.controller
        val p = h.places()
        h.startAt(p[1])
        if (c.state.value.bookmarked) { // a leftover of an interrupted earlier run on this very page
            h.sc.onActivity { it.controller.toggleBookmark() }
            withTimeout(5_000) { c.state.first { !it.bookmarked } }
        }
        val before = c.state.value.bookmarks.map { it.id }.toSet()
        val blockAt = h.spot().block
        assertNotNull("block at the bookmark", blockAt)
        h.sc.onActivity { it.controller.toggleBookmark() }
        val b = withTimeout(5_000) { c.state.first { st -> st.bookmarked && st.bookmarks.any { it.id !in before } } }.bookmarks.first { it.id !in before }
        madeBookmarks += b.id

        h.startAt(p[p.size - 2])
        withTimeout(5_000) { c.state.first { !it.bookmarked } }
        h.drain()
        h.sc.onActivity { it.controller.showToc(); it.controller.selectContentsTab(ContentsTab.Bookmarks); it.controller.onBookmarkSelected(b) }
        h.landing("bookmark") { it.sectionIndex == b.spineIndex }
        withTimeout(3_000) { c.state.first { !it.tocVisible } }
        h.assertLandsAndHolds("bookmark row", b.spineIndex) {
            when {
                !c.state.value.bookmarked -> "the page is not marked as bookmarked"
                !h.sc.blockVisible(blockAt!!) -> "bookmarked block '$blockAt' not visible (first block ${h.sc.firstVisibleBlock()})"
                else -> null
            }
        }
    }

    // ---- 5. Highlight ----

    @Test fun highlight_row_lands_and_holds() = run(warAndPeace(), WAR_AND_PEACE) { h ->
        val c = h.a.controller
        val p = h.places()
        val wp = warAndPeace()
        val id = bookId(wp)
        h.startAt(p[1])
        val word = "the"
        val before = graph.annotations.annotations(id).first().map { it.id }.toSet()
        h.drain()
        assertEquals("'$word' on screen", "true", h.sc.js("window.__readerTest.selectWord(${JSONObject.quote(word)})"))
        h.next(5_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi != null } }
        h.sc.onActivity { it.controller.highlightSelection() }
        val a = withTimeout(5_000) { graph.annotations.annotations(id).first { l -> l.any { it.id !in before } } }.first { it.id !in before }
        madeAnnotations += a.id
        h.sc.onActivity { it.controller.dismissHighlightPicker() }
        assertEquals(word, a.text)

        h.startAt(p[p.size - 2])
        withTimeout(5_000) { c.state.first { st -> st.annotations.any { it.id == a.id } } }
        h.drain()
        h.sc.onActivity { it.controller.showToc(); it.controller.selectContentsTab(ContentsTab.Highlights); it.controller.onAnnotationSelected(a) }
        h.landing("highlight") { it.sectionIndex == a.spineIndex }
        withTimeout(3_000) { c.state.first { !it.tocVisible } }
        h.assertLandsAndHolds("highlight row", a.spineIndex) {
            val d = h.sc.jsValue("window.__readerTest.annotation(${JSONObject.quote(a.id)})") as? JSONObject
            val range = d?.optJSONObject("range")
            when {
                d == null -> "the highlight is not drawn"
                d.optString("text") != word -> "the highlight covers '${d.optString("text")}'"
                range == null -> "the highlight's range has no box"
                !range.put("vw", d.getDouble("vw")).put("vh", d.getDouble("vh")).onScreen() -> "the highlight is not on screen ($range)"
                else -> null
            }
        }
    }

    // ---- 6. Seek ----

    @Test fun slider_seek_forward_and_back_lands_and_holds() = run(warAndPeace(), WAR_AND_PEACE) { h ->
        val p = h.places()
        h.startAt(p.first())
        for ((f, what) in listOf(0.75 to "seek forward to 75 %", 0.25 to "seek back to 25 %")) {
            h.drain()
            h.sc.onActivity { it.controller.seek(f) }
            val r = h.landing(what) { abs(it.fraction - f) < 0.02 }
            h.assertLandsAndHolds(what, r.sectionIndex) {
                h.drain()
                val at = h.at!!.fraction
                when {
                    abs(at - f) >= 0.02 -> "at fraction $at"
                    h.sc.firstVisibleBlock() == null -> "no text on screen"
                    else -> null
                }
            }
        }
    }

    // ---- 7. Resume ----

    @Test fun resume_after_close_and_reopen_lands_and_holds() = run(warAndPeace(), WAR_AND_PEACE) { h ->
        val p = h.places()
        val wp = warAndPeace()
        val from = h.startAt(p[p.size / 2])
        h.send(ToWeb.Next()) // a place inside the section, not a Contents target
        h.relocate { it.cfi != from.cfi }
        h.still()
        val stopped = h.spot()
        val block = stopped.block
        assertNotNull("block where the reader stopped", block)

        h.sc.onActivity { it.goBack() }
        withTimeout(10_000) { h.a.controller.state.first { !it.open } }

        // Reopen without any jump of the test's own (open() would settle with a goTo to wherever it landed). The open
        // waits for the close-out, which stores the last position (ReaderController.open).
        h.drain()
        h.sc.onActivity { it.openBook(SourceRef.File(wp.path)) }
        h.landing("resume", 20_000) { true }
        withTimeout(15_000) { h.a.controller.state.first { !it.loading && it.title.contains(WAR_AND_PEACE, ignoreCase = true) && it.toc.isNotEmpty() } }
        h.still()
        h.assertLandsAndHolds("resume", stopped.relocated) {
            if (h.sc.blockVisible(block!!)) null else "block '$block' not visible (first block ${h.sc.firstVisibleBlock()})"
        }
    }
}
