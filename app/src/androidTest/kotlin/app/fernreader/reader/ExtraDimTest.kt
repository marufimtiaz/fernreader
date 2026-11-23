package app.fernreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.ReaderSettings
import app.fernreader.core.data.ReaderSheetTab
import app.fernreader.core.data.ReadingThemeId
import app.fernreader.reader.bridge.ToWeb
import app.fernreader.settled
import app.fernreader.ui.Copy
import app.fernreader.waitFresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * C-110 Extra dim: with it on, the page's text and an image's pixels are darker by about the chosen factor and the
 * page's black stays black; the reader's controls (the settings sheet) are not dimmed; turning it off restores the
 * exact pixels; nothing relayouts (the same page, the same position, the image and the text in the same place).
 * Pixels are read from screenshots: Extra dim darkens the colours at paint time (a brightness filter on the section's
 * frame, see reader-web styles.ts pagePaper), so the text's computed colour stays the book's and the filter is read
 * beside it. Also logs the frame times of page turns with and without it (gfxinfo; informational, not asserted).
 */
@RunWith(AndroidJUnit4::class)
class ExtraDimTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    private fun shell(cmd: String): String {
        val fd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }.trim()
    }

    private suspend fun ActivityScenario<MainActivity>.jsValue(expr: String): Any? =
        JSONTokener(js("JSON.stringify($expr)")).nextValue().let { if (it is String) JSONTokener(it).nextValue() else null }

    private data class Box(val x: Double, val y: Double, val w: Double, val h: Double)
    private fun JSONObject.box(k: String) = optJSONObject(k)?.let { Box(it.getDouble("x"), it.getDouble("y"), it.getDouble("w"), it.getDouble("h")) }

    /** The page as painted (web probe `dim`). */
    private data class Painted(val textColor: String, val filter: String, val overlayFilter: String?, val paper: String, val image: Box, val line: Box, val dpr: Double)

    private suspend fun ActivityScenario<MainActivity>.painted(): Painted {
        val o = checkNotNull(jsValue("window.__readerTest.dim()") as? JSONObject) { "no section shown" }
        return Painted(o.getString("textColor"), o.getString("filter"), o.optString("overlayFilter").takeIf { !o.isNull("overlayFilter") },
            o.getString("paper"), checkNotNull(o.box("image")) { "no image" }, checkNotNull(o.box("line")) { "no text line" }, o.getDouble("dpr"))
    }

    /** What the screen shows: the image's middle (mean of 5 x 5 px), the brightest pixel of the first text line, the black margin. */
    private data class Pixels(val image: Int, val text: Int, val margin: Int, val sheet: List<Int>)

    private fun ActivityScenario<MainActivity>.pixels(p: Painted, sheetRows: List<Int> = emptyList()): Pixels {
        val loc = IntArray(2)
        onActivity { it.reader.webView.getLocationOnScreen(loc) }
        val bmp = inst.uiAutomation.takeScreenshot().copy(Bitmap.Config.ARGB_8888, false)
        try {
            fun sx(x: Double) = (loc[0] + x * p.dpr).roundToInt()
            fun sy(y: Double) = (loc[1] + y * p.dpr).roundToInt()
            val cx = sx(p.image.x + p.image.w / 2); val cy = sy(p.image.y + p.image.h / 2)
            var r = 0; var g = 0; var b = 0
            for (dx in -2..2) for (dy in -2..2) bmp.getPixel(cx + dx, cy + dy).let { r += Color.red(it); g += Color.green(it); b += Color.blue(it) }
            val image = Color.rgb(r / 25, g / 25, b / 25)
            var text = Color.BLACK
            for (x in sx(p.line.x) until sx(p.line.x + p.line.w)) for (y in sy(p.line.y) until sy(p.line.y + p.line.h)) {
                val c = bmp.getPixel(x, y)
                if (Color.red(c) + Color.green(c) + Color.blue(c) > Color.red(text) + Color.green(text) + Color.blue(text)) text = c
            }
            val margin = bmp.getPixel((bmp.width * 0.02).toInt(), sy(p.image.y + p.image.h / 2))
            return Pixels(image, text, margin, sheetRows.map { bmp.getPixel((bmp.width * 0.02).toInt(), it) })
        } finally { bmp.recycle() }
    }

    private fun hex(c: Int) = "#%06x".format(c and 0xffffff)

    /** Each channel of [actual] is [f] x [base]'s, within [tol] (rounding, glyph antialiasing). */
    private fun assertScaled(what: String, base: Int, f: Double, actual: Int, tol: Int) {
        val ok = listOf(Color::red, Color::green, Color::blue).all { ch -> abs(ch(actual) - ch(base) * f) <= tol }
        assertTrue("$what: ${hex(actual)} is not ${hex(base)} x $f (±$tol)", ok)
    }

    /** Settings applied and the page repainted: the probe shows [filter] and the screen has stopped changing. */
    private suspend fun ActivityScenario<MainActivity>.repainted(filter: String, rows: List<Int> = emptyList()): Pair<Painted, Pixels> {
        withTimeout(5_000) { while (painted().filter != filter) delay(50) }
        var last: Pixels? = null
        repeat(40) {
            val p = painted(); val px = pixels(p, rows)
            if (px == last) return p to px
            last = px; delay(100)
        }
        error("the page kept changing after the filter became $filter")
    }

    private fun desc(text: String): UiObject2 = checkNotNull(dev.settled(By.desc(text), 5_000)) { "no '$text' on screen" }

    /** Scrolls the open Display tab until [text] (a content description) is on screen. */
    private fun sheetShowDesc(text: String): UiObject2 {
        val top = checkNotNull(dev.settled(By.text(Copy.general), 5_000)).visibleBounds.bottom
        repeat(10) {
            if (waitFresh(300) { dev.hasObject(By.desc(text)) }) return desc(text)
            val x = dev.displayWidth / 8
            dev.swipe(x, dev.displayHeight - (dev.displayHeight - top) / 4, x, top + (dev.displayHeight - top) / 4, 40)
        }
        return desc(text)
    }

    private fun withBook(theme: ReadingThemeId, chapters: Int = 2, paragraphs: Int = 30, body: suspend (ActivityScenario<MainActivity>, ReaderHarness) -> Unit) = runBlocking {
        val f = TestBooks.write(inst.targetContext, "extradim.epub", imageBook(chapters, paragraphs))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { s -> s.copy(theme = theme) } }
                h.open(f)
                body(sc, h)
            } finally {
                sc.onActivity { it.controller.hideSheet(); it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    @Test fun extra_dim_darkens_text_and_images_keeps_black_and_controls_and_never_moves_the_page() = withBook(ReadingThemeId.BLACK) { sc, h ->
        val at = h.quiet()
        val where = sc.hook("page()").toString()
        val block = sc.jsValue("window.__readerTest.firstVisibleBlock()")
        val (off, base) = sc.repainted("none")
        Log.i(TAG, "C110 off: $off $base")
        assertEquals("Black theme paper", "rgb(0, 0, 0)", off.paper)
        assertEquals("black margin", Color.BLACK, base.margin)
        assertScaled("the image as drawn", IMAGE, 1.0, base.image, 3)
        assertScaled("the text as drawn", Color.rgb(0xC4, 0xC0, 0xB8), 1.0, base.text, 8)

        // On, from the switch in the display panel: the default level, 50 %.
        sc.onActivity { it.controller.showSheet(ReaderSheetTab.DISPLAY) }
        val switch = sheetShowDesc(Copy.extraDim)
        val rows = listOf(switch.visibleBounds.top - 4, dev.displayHeight - 8) // the sheet's surface at its left edge
        val (_, sheetOff) = sc.repainted("none", rows)
        switch.click()
        withTimeout(5_000) { h.a.controller.settings.first { it.extraDim } }
        val (half, halfPx) = sc.repainted("brightness(0.5)", rows)
        Log.i(TAG, "C110 0.5: $half $halfPx")
        assertEquals("the computed text colour is the book's (dimmed when painted)", off.textColor, half.textColor)
        half.overlayFilter?.let { assertEquals("highlight layer dims with the page", "brightness(0.5)", it) }
        assertScaled("image at 50 %", base.image, 0.5, halfPx.image, 3)
        assertScaled("text at 50 %", base.text, 0.5, halfPx.text, 6)
        assertEquals("black stays black", Color.BLACK, halfPx.margin)
        assertEquals("the settings sheet is not dimmed", sheetOff.sheet.map(::hex), halfPx.sheet.map(::hex))

        // Strong dimming, live from the level.
        sc.onActivity { it.controller.updateSettings { s -> s.copy(extraDimLevel = ReaderSettings.DIM_STRONG) } }
        val (strong, strongPx) = sc.repainted("brightness(0.15)", rows)
        Log.i(TAG, "C110 0.15: $strong $strongPx")
        assertScaled("image at 15 %", base.image, 0.15, strongPx.image, 3)
        assertScaled("text at 15 %", base.text, 0.15, strongPx.text, 4)
        assertEquals("black stays black", Color.BLACK, strongPx.margin)

        // Off again from the switch: the exact pixels come back.
        switch.click()
        withTimeout(5_000) { h.a.controller.settings.first { !it.extraDim } }
        val (back, backPx) = sc.repainted("none", rows)
        assertEquals("image restored exactly", hex(base.image), hex(backPx.image))
        assertEquals("text restored exactly", hex(base.text), hex(backPx.text))
        assertEquals(off.paper, back.paper)

        // No relayout: the same page and position, and the image and the text line where they were.
        for (p in listOf(half, strong, back)) {
            assertEquals("image box", off.image, p.image)
            assertEquals("text line box", off.line, p.line)
        }
        assertEquals("page", where, sc.hook("page()").toString())
        assertEquals("first visible block", block, sc.jsValue("window.__readerTest.firstVisibleBlock()"))
        h.drain()
        assertEquals("position", at.cfi, h.at?.cfi)
        h.a.settingsStored()
        assertEquals(false, h.a.controller.settings.value.extraDim)
        assertEquals(ReaderSettings.DIM_STRONG, h.a.controller.settings.value.extraDimLevel, 0.0) // the level is kept
    }

    /**
     * What Extra dim costs at a page turn: gfxinfo frame times over the same turns (in-section and chapter boundaries,
     * forwards then back), off and on, twice. Logged for the report (tag EPR, "C110 ..."); emulator timings are noisy, not asserted.
     */
    @Test fun page_turn_frames_with_and_without_extra_dim() = withBook(ReadingThemeId.LIGHT, chapters = 6, paragraphs = 12) { sc, h ->
        val pkg = inst.targetContext.packageName
        suspend fun turns(dim: Boolean): String {
            sc.onActivity { it.controller.updateSettings { s -> s.copy(extraDim = dim, extraDimLevel = 0.3) } }
            withTimeout(5_000) { while (sc.painted().filter != if (dim) "brightness(0.3)" else "none") delay(50) }
            h.quiet()
            shell("dumpsys gfxinfo $pkg reset")
            for (msg in List(10) { ToWeb.Next() } + List(10) { ToWeb.Prev() }) {
                h.drain(); h.send(msg); h.relocate(); delay(450)
            }
            val g = shell("dumpsys gfxinfo $pkg")
            fun v(label: String) = Regex("$label: ?([0-9]+)").find(g)?.groupValues?.get(1)
            return "frames=${v("Total frames rendered")} janky=${Regex("Janky frames: ([0-9]+ \\([0-9.]+%\\))").find(g)?.groupValues?.get(1)} " +
                "p50=${v("50th percentile")}ms p90=${v("90th percentile")}ms p95=${v("95th percentile")}ms p99=${v("99th percentile")}ms"
        }
        repeat(2) { round ->
            Log.i(TAG, "C110 turns round ${round + 1} off: ${turns(false)}")
            Log.i(TAG, "C110 turns round ${round + 1} on:  ${turns(true)}")
        }
    }

    private companion object {
        const val TAG = "EPR" // kept by the suite's logcat filter; messages start with C110
        val IMAGE = Color.rgb(200, 150, 100)

        /** A book whose chapters each start with a short paragraph and a 160 x 160 image of one colour ([IMAGE]). */
        fun imageBook(chapters: Int, paragraphs: Int): ByteArray {
            val png = ByteArrayOutputStream().also { o ->
                Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(IMAGE) }.compress(Bitmap.CompressFormat.PNG, 100, o)
            }.toByteArray()
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                val mt = "application/epub+zip".toByteArray()
                z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mt) }.value })
                z.write(mt); z.closeEntry()
                fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
                fun put(name: String, text: String) = put(name, text.toByteArray())
                put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
                val items = (1..chapters).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
                val spine = (1..chapters).joinToString("") { """<itemref idref="c$it"/>""" }
                put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:extradim-${System.nanoTime()}</dc:identifier><dc:title>Extra Dim</dc:title><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="img" href="square.png" media-type="image/png"/>$items</manifest><spine>$spine</spine></package>""")
                put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${(1..chapters).joinToString("") { """<li><a href="c$it.xhtml">Chapter $it</a></li>""" }}</ol></nav></body></html>""")
                put("OEBPS/square.png", png)
                for (c in 1..chapters) {
                    val body = (1..paragraphs).joinToString("") { "<p>Chapter $c, paragraph $it. " + "Lorem ipsum dolor sit amet. ".repeat(8) + "</p>" }
                    put("OEBPS/c$c.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $c</title></head><body><p>Mostly harmless words to measure.</p><div><img src="square.png" alt="" style="width: 160px; height: 160px"/></div>$body</body></html>""")
                }
            }
            return out.toByteArray()
        }
    }
}
