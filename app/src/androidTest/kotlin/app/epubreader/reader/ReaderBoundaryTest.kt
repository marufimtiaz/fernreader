package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** P-026 / F-031 basis: neighbour sections are preloaded and boundary turns bring them in (War and Peace); C-071: with a slide. */
@RunWith(AndroidJUnit4::class)
class ReaderBoundaryTest {
    private companion object { const val SLIDE_MS = 300.0 } // paginator.js SLIDE_MS
    private suspend fun ReaderHarness.loaded(): List<Int> = JSONArray(sc.js("window.__readerTest.loaded()")).let { a -> List(a.length()) { a.getInt(it) } }

    /** Waits until the neighbour after the current section has a loaded document. */
    private suspend fun ReaderHarness.awaitPreloaded(index: Int) {
        repeat(100) { if (index in loaded()) return; delay(50) }
        error("section $index not preloaded; loaded=${loaded()}")
    }

    @Test fun ten_boundaries_in_order(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(wp)
            var at = h.tocJump("BOOK ONE: 1805")
            repeat(10) {
                h.lastPage()
                h.awaitPreloaded(at.sectionIndex + 1)
                h.send(ToWeb.Next())
                val next = h.relocate(5_000) { it.sectionIndex != at.sectionIndex }
                assertEquals(at.sectionIndex + 1, next.sectionIndex)
                h.painted()
                at = h.settle()
            }
            h.stop()
        }
    }

    @Test fun decoration_in_preloaded_section_survives_swap(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(wp)
            val at = h.tocJump("BOOK ONE: 1805")
            h.lastPage()
            val next = at.sectionIndex + 1
            h.awaitPreloaded(next)
            val cfi = org.json.JSONTokener(sc.js("window.__readerTest.firstParagraphCfi($next)")).nextValue() as String
            sc.js("window.__readerTest.decorate(${org.json.JSONObject.quote(cfi)})")
            delay(300)
            assertTrue("drawn while preloaded", sc.js("window.__readerTest.overlayCount($next)").toInt() > 0)
            h.send(ToWeb.Next())
            h.relocate(5_000) { it.sectionIndex == next }
            h.painted()
            assertTrue("still drawn after the swap", sc.js("window.__readerTest.overlayCount($next)").toInt() > 0)
            h.stop()
        }
    }

    private suspend fun ReaderHarness.slideFrames(): List<JSONObject> = JSONArray(sc.js("JSON.stringify(window.__readerTest.slide() ?? [])").let { org.json.JSONTokener(it).nextValue() as String })
        .let { a -> List(a.length()) { a.getJSONObject(it) } }

    /**
     * Checks one boundary slide: several distinct in-between frames (not one swap), the two pages exactly one width apart
     * in every frame (no gap, so no blank shows), moving the way the turn goes ([sign] -1 = content to the left), ending in place.
     */
    private fun assertSlide(frames: List<JSONObject>, sign: Int) {
        // Frame-rate independent (a 300 ms slide gets ~18 frames on a phone, 2-3 on this emulator where a WebView frame
        // costs ~120 ms): at least one frame between start and end (not one swap), the moving frames span most of the
        // slide's duration (not a short jerk), and every frame moves further in the turn's direction (no back-and-forth).
        val mid = frames.map { it.getDouble("t") }.filter { it > 0.0 && it < 1.0 }.distinct()
        assertTrue("an in-between frame, not one swap: $frames", mid.isNotEmpty())
        val moving = frames.filter { it.getDouble("t") > 0.0 }
        val span = moving.last().getDouble("at") - moving.first().getDouble("at")
        assertTrue("the slide's moving frames span ≥ 70 % of $SLIDE_MS ms: $span ms, $frames", span >= 0.7 * SLIDE_MS)
        for (k in 1 until frames.size) {
            val a = frames[k - 1]; val b = frames[k]
            // the incoming page moves from -sign * width towards 0 in the turn's direction (sign): never back
            assertTrue("monotonic at frame $k: $frames", b.getDouble("t") >= a.getDouble("t") && sign * (b.getDouble("next") - a.getDouble("next")) >= -0.5)
        }
        val width = Math.abs(frames.first().getDouble("next"))
        assertTrue("starts one width away: $frames", width > 100)
        for (f in frames) assertEquals("adjacent pages at t=${f.getDouble("t")}", sign * width, f.getDouble("old") - f.getDouble("next"), 0.5)
        assertEquals(1.0, frames.last().getDouble("t"), 1e-9)
        assertEquals(0.0, frames.last().getDouble("next"), 0.5)
    }

    /** Starts sampling the slide's on-screen geometry on every frame for 1.5 s (`window.__geo`). */
    private suspend fun ReaderHarness.sampleGeometry() = sc.js("""window.__geo = []; window.__geoEnd = performance.now() + 1500;
        (function loop() { const g = window.__readerTest.slideGeometry(); if (g) window.__geo.push(g);
          if (performance.now() < window.__geoEnd) requestAnimationFrame(loop) })(); true""")

    /** The sampled frames: both pages full height (no partial page), the old one still attached (no blank half). */
    private suspend fun ReaderHarness.assertGeometry() {
        val a = JSONArray(org.json.JSONTokener(sc.js("JSON.stringify(window.__geo)")).nextValue() as String)
        assertTrue("geometry sampled during the slide: ${a.length()} frames", a.length() >= 3)
        for (i in 0 until a.length()) {
            val g = a.getJSONObject(i)
            val h = g.getJSONObject("container").getDouble("height")
            assertEquals("old page height, frame $i", h, g.getJSONObject("old").getDouble("height"), 1.0)
            assertEquals("new page height, frame $i", h, g.getJSONObject("next").getDouble("height"), 1.0)
            assertTrue("old page attached, frame $i", g.getBoolean("oldConnected"))
        }
    }

    /** C-071 (F-032): a turn across a chapter boundary slides like any other turn, forward and back. */
    @Test fun boundary_turns_slide_both_ways(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(wp)
            val at = h.tocJump("BOOK ONE: 1805")
            h.lastPage()
            h.awaitPreloaded(at.sectionIndex + 1)
            h.sampleGeometry()
            h.send(ToWeb.Next())
            h.relocate(5_000) { it.sectionIndex == at.sectionIndex + 1 }
            delay(1_600) // the slide is 300 ms; the sampler runs 1.5 s
            assertSlide(h.slideFrames(), -1) // left-to-right book: next moves the content left
            h.assertGeometry()
            h.settle()
            h.awaitPreloaded(at.sectionIndex)
            h.sampleGeometry()
            h.send(ToWeb.Prev())
            h.relocate(5_000) { it.sectionIndex == at.sectionIndex }
            delay(1_600)
            assertSlide(h.slideFrames(), 1)
            h.assertGeometry()
            h.stop()
        }
    }

    /** C-071: with "no animation" every turn is instant, boundary turns included. */
    @Test fun without_animation_a_boundary_turn_is_instant(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            sc.onActivity { it.controller.updateSettings { s -> s.copy(pageAnimation = app.epubreader.core.data.PageAnimation.NONE) } }
            try {
                h.open(wp)
                val at = h.tocJump("BOOK ONE: 1805")
                h.lastPage()
                h.awaitPreloaded(at.sectionIndex + 1)
                h.send(ToWeb.Next())
                h.relocate(5_000) { it.sectionIndex == at.sectionIndex + 1 }
                delay(600)
                assertEquals("no slide frames", 0, h.slideFrames().size)
            } finally {
                sc.onActivity { it.controller.updateSettings { s -> s.copy(pageAnimation = app.epubreader.core.data.PageAnimation.SLIDE) } }
                h.stop()
            }
        }
    }
}
