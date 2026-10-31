package app.epubreader.reader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.core.data.CfiOrder
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-081: a vertical-rl book (kusamakura, page-progression rtl) is paged right-to-left: horizontal pages, "next" brings
 * in the page that was to the LEFT, text on a page runs from the right edge to the left edge, relocate CFIs increase,
 * the neighbour section is preloaded and swapped in at the boundary (P-026 path), the position resumes.
 * C-032: its pages are counted per section and the count equals the pages turned through (C-003).
 */
@RunWith(AndroidJUnit4::class)
class VerticalWritingTest {
    private fun kusamakura(): File {
        warAndPeace() // storage access
        return File("/sdcard/Books/epub3-samples/kusamakura-japanese-vertical-writing.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
    }

    /** Geometry of the shown page in host CSS px: the frame's left edge, where the visible text starts and ends. */
    private suspend fun ActivityScenario<MainActivity>.shown(): JSONObject = JSONObject(JSONTokener(js("""JSON.stringify((() => {
        const v = document.querySelector('foliate-view'), r = v.renderer, range = v.lastLocation?.range
        const doc = range?.startContainer?.ownerDocument, fr = doc?.defaultView?.frameElement?.getBoundingClientRect()
        const rects = range ? [...range.getClientRects()].filter(x => x.width > 0 && x.height > 0) : []
        const o = window.__vt, n = range
        const after = o && n && o.startContainer.ownerDocument === doc ? o.comparePoint(n.startContainer, n.startOffset) : null
        window.__vt = range?.cloneRange()
        return { vp: !!r.verticalPages, dir: r.getAttribute('dir'), w: innerWidth, frameLeft: fr?.left ?? null,
          startX: rects.length ? fr.left + rects[0].right : null, endX: rects.length ? fr.left + rects[rects.length - 1].left : null,
          startsAfterPrevious: after, text: range?.toString().slice(0, 12) ?? null }
    })())""")).nextValue() as String)

    private suspend fun ReaderHarness.turn(next: Boolean) = run {
        val from = at!!
        send(if (next) ToWeb.Next() else ToWeb.Prev())
        relocate(5_000) { it.cfi != from.cfi }
        delay(450) // the slide animation (300 ms) and the paint
        at!!
    }

    @Test fun pages_progress_right_to_left_and_resume(): Unit = runBlocking {
        val book = kusamakura()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(book)
                val linear = JSONArray(JSONTokener(sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String)
                    .let { a -> List(a.length()) { a.getInt(it) } }
                // a long chapter: the biggest one by counted pages (counting runs in the background, C-032)
                withTimeout(30_000) { h.a.controller.page.first { it.exact } }
                val chapter = linear.maxBy { h.a.controller.countedPages(it) ?: 0 }
                h.drain(); sc.js("window.__readerTest.goIndex($chapter)")
                h.relocate(5_000) { it.sectionIndex == chapter && it.pages > 0 }
                h.settle(); delay(300)
                val p1 = sc.shown()
                Log.i("EPR", "vertical page 1: $p1")
                assertTrue("vertical pages on: $p1", p1.getBoolean("vp"))
                assertEquals("paged like a right-to-left book", "rtl", p1.getString("dir"))
                // the page's text runs from the right side to the left side
                assertTrue("text starts right of where it ends: $p1", p1.getDouble("startX") > p1.getDouble("endX"))
                assertTrue("starts on the right half: $p1", p1.getDouble("startX") > p1.getInt("w") / 2.0)
                assertTrue("ends on the left half: $p1", p1.getDouble("endX") < p1.getInt("w") / 2.0)

                var prev = h.at!!
                var prevShown = p1
                repeat(3) {
                    val loc = h.turn(next = true)
                    val s = sc.shown()
                    Log.i("EPR", "after next: page ${loc.page}/${loc.pages} $s")
                    assertEquals(chapter, loc.sectionIndex)
                    assertEquals(prev.page + 1, loc.page)
                    assertTrue("CFI increases: ${prev.cfi} -> ${loc.cfi}", CfiOrder.compare(loc.cfi, prev.cfi) > 0)
                    // the pages slid right: the new page came from the left of the old one
                    val moved = s.getDouble("frameLeft") - prevShown.getDouble("frameLeft")
                    assertTrue("content moved one page to the right: $moved", moved > s.getInt("w") * 0.8)
                    assertTrue("the text continues where the last page ended: $s", s.getInt("startsAfterPrevious") >= 0)
                    prev = loc; prevShown = s
                }
                val back = h.turn(next = false)
                assertEquals(prev.page - 1, back.page)
                assertTrue("prev moves the pages left", sc.shown().getDouble("frameLeft") < prevShown.getDouble("frameLeft"))

                // boundary: the next chapter (also vertical-rl) is preloaded and swapped in at the last page
                val nextChapter = linear[linear.indexOf(chapter) + 1]
                h.lastPage(); h.settle(); delay(300)
                withTimeout(5_000) { while (!sc.js("JSON.stringify(window.__readerTest.loaded())").contains("$nextChapter")) delay(50) }
                val first = h.turn(next = true)
                assertEquals(nextChapter, first.sectionIndex)
                assertEquals(1, first.page)
                val sn = sc.shown()
                assertTrue("next chapter paged right-to-left too: $sn", sn.getBoolean("vp") && sn.getDouble("startX") > sn.getDouble("endX"))

                // resume: three pages in, another book, then this one again (cold) lands on the same page
                repeat(3) { h.turn(next = true) }
                val saved = h.at!!
                h.open(warAndPeace())
                val resumed = h.open(book)
                Log.i("EPR", "resume ${saved.cfi} page ${saved.page} -> ${resumed.cfi} page ${resumed.page}")
                assertEquals(saved.sectionIndex, resumed.sectionIndex)
                assertEquals(saved.page, resumed.page)
            } finally { h.stop() }
        }
    }

    /** C-032 + C-003: every section's counted pages equal the pages the reader turns through. */
    @Test fun counted_pages_equal_turned_pages(): Unit = runBlocking {
        val book = kusamakura()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(book)
                val exact = withTimeout(30_000) { h.a.controller.page.first { it.exact } }
                Log.i("EPR", "kusamakura exact total: $exact")
                val linear = JSONArray(JSONTokener(sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String)
                    .let { a -> List(a.length()) { a.getInt(it) } }
                val wrong = mutableListOf<String>()
                for (i in linear) {
                    h.drain()
                    sc.js("window.__readerTest.goIndex($i)")
                    h.relocate(5_000) { it.sectionIndex == i && it.pages > 0 }
                    var visible = -1
                    var tries = 0
                    while (tries++ < 20) { // two equal readings 150 ms apart
                        delay(150)
                        val p = sc.hook("page()").getInt("textPages")
                        if (p == visible) break
                        visible = p
                    }
                    val counted = h.a.controller.countedPages(i)
                    if (counted != visible) wrong += "section $i: counted $counted, visible $visible"
                }
                // and turned through: in the longest section, `next` from page 1 reaches the counted last page
                val longest = linear.maxBy { h.a.controller.countedPages(it) ?: 0 }
                val counted = h.a.controller.countedPages(longest)!!
                h.drain(); sc.js("window.__readerTest.goIndex($longest)")
                h.relocate(5_000) { it.sectionIndex == longest && it.pages > 0 }
                h.settle()
                var loc = h.at!!
                while (loc.sectionIndex == longest && loc.page < loc.pages) {
                    val from = loc
                    h.send(ToWeb.Next())
                    loc = h.relocate(5_000) { it.cfi != from.cfi }
                }
                val turned = loc.page
                Log.i("EPR", "vertical agreement mismatches: $wrong; section $longest counted $counted turned $turned (sections ${linear.size})")
                assertEquals(emptyList<String>(), wrong)
                assertEquals(counted, turned)
            } finally { h.stop() }
        }
    }
}
