package app.epubreader.reader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.core.data.Align
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C-072: paged and scrolled flow render the same typography and, on a phone in portrait, the same text column, so a
 * paragraph breaks its lines at the same words. Checked on one War and Peace passage at the default settings and at
 * the user report's settings (justified, line spacing 1.5, margins 5 %).
 */
@RunWith(AndroidJUnit4::class)
class FlowTypographyTest {
    private val styleKeys = listOf("fontFamily", "fontSize", "fontWeight", "letterSpacing", "wordSpacing", "lineHeight", "textAlign",
        "rootTransform", "rootZoom", "frameTransform", "frameZoom", "textSizeAdjust")

    private suspend fun ReaderHarness.typography(index: Int): JSONObject {
        var last: JSONObject? = null
        repeat(20) { // two equal readings 150 ms apart (fonts settle after the relocate)
            delay(150)
            val t = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.typography($index))")).nextValue() as String)
            if (last?.toString() == t.toString()) return t
            last = t
        }
        return checkNotNull(last)
    }

    private suspend fun ReaderHarness.measure(flow: Flow, index: Int): JSONObject {
        sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = flow) } }
        delay(800)
        drain()
        sc.js("window.__readerTest.goIndex($index)")
        relocate(10_000) { it.sectionIndex == index }
        return typography(index)
    }

    private fun lines(o: JSONObject) = o.getJSONArray("lines").let { a -> List(a.length()) { a.getString(it) } }

    @Test fun pages_and_scroll_give_the_same_typography_and_line_breaks(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(wp)
                val index = h.tocJump("BOOK ONE: 1805").sectionIndex + 1 // chapter 1: long paragraphs
                val problems = mutableListOf<String>()
                for ((name, settings) in listOf(
                    "defaults" to { s: ReaderSettings -> ReaderSettings(flow = s.flow) },
                    "justified, 1.5, 5 %" to { s: ReaderSettings -> ReaderSettings(flow = s.flow, align = Align.JUSTIFY, lineHeight = 1.5, marginPercent = 5) },
                )) {
                    sc.onActivity { it.controller.updateSettings(settings) }
                    val paged = h.measure(Flow.PAGED, index)
                    val scrolled = h.measure(Flow.SCROLLED, index)
                    Log.i("EPR", "C-072 $name paged=$paged")
                    Log.i("EPR", "C-072 $name scrolled=$scrolled")
                    for (k in styleKeys) if (paged.optString(k) != scrolled.optString(k)) problems += "$name $k: paged ${paged.optString(k)}, scrolled ${scrolled.optString(k)}"
                    for (k in listOf("columnWidth", "columnLeft")) {
                        val d = kotlin.math.abs(paged.getDouble(k) - scrolled.getDouble(k))
                        if (d > 1.0) problems += "$name $k: paged ${paged.getDouble(k)}, scrolled ${scrolled.getDouble(k)}"
                    }
                    if (kotlin.math.abs(paged.getDouble("wordWidth") - scrolled.getDouble("wordWidth")) > 0.1) problems += "$name word width differs (text autosizing?)"
                    val lp = lines(paged); val ls = lines(scrolled)
                    assertTrue("$name: a multi-line paragraph", lp.size >= 4)
                    if (lp != ls) problems += "$name line starts: paged $lp, scrolled $ls"
                }
                assertEquals(emptyList<String>(), problems)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.stop()
            }
        }
    }

    /**
     * C-082: the side margin is the real edge-to-text distance in both flows (m % of the width per side), whatever
     * horizontal margins and padding the book puts on body and on a wrapper around the whole content, and a slider
     * change applies live in scrolled flow; an indent inside the text (a blockquote) stays. C-088: a title page built as
     * one narrow centred div (60 %, margin auto) stays centred in the text column. Synthetic book: the title page is
     * section 0, the text chapter section 1.
     */
    @Test fun side_margin_is_the_edge_to_text_distance_in_both_flows(): Unit = runBlocking {
        warAndPeace() // storage access
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = app.epubreader.TestBooks.write(ctx, "c082-margins.epub", marginsBook())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(file, withToc = false)
                val problems = mutableListOf<String>()
                for (flow in listOf(Flow.PAGED, Flow.SCROLLED)) {
                    sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = flow, marginPercent = 5) } }
                    delay(800); h.drain(); sc.js("window.__readerTest.goIndex(0)"); h.relocate(10_000) { it.sectionIndex == 0 }
                    for (m in listOf(0, 5, 10)) {
                        // C-088: the title page's narrow centred div, equal gaps to the text column's edges
                        sc.onActivity { it.controller.updateSettings { s -> s.copy(marginPercent = m) } }
                        delay(900)
                        val box = JSONTokener(sc.js("JSON.stringify(window.__readerTest.blockBox(0, 'div.title'))")).nextValue().let { JSONObject(it as String) }
                        val vw = (JSONTokener(sc.js("JSON.stringify(window.innerWidth)")).nextValue() as String).toDouble()
                        val colLeft = vw * m / 100; val colRight = vw - colLeft
                        val gapL = box.getDouble("left") - colLeft; val gapR = colRight - box.getDouble("right")
                        val w = box.getDouble("right") - box.getDouble("left")
                        Log.i("EPR", "C-088 $flow $m %: title ${box.getDouble("left")}..${box.getDouble("right")} gaps $gapL / $gapR (column $colLeft..$colRight)")
                        if (kotlin.math.abs(gapL - gapR) > 1.0) problems += "$flow $m %: title page not centred: gaps $gapL left, $gapR right"
                        if (kotlin.math.abs(w - 0.6 * (colRight - colLeft)) > 2.0) problems += "$flow $m %: title width $w, want 60 % of ${colRight - colLeft}"
                    }
                    sc.js("window.__readerTest.goIndex(1)"); h.relocate(10_000) { it.sectionIndex == 1 }
                    for (m in listOf(0, 5, 10)) {
                        // a slider change while reading (scrolled flow: no re-open, no flow switch)
                        sc.onActivity { it.controller.updateSettings { s -> s.copy(marginPercent = m) } }
                        delay(900)
                        val t = h.typography(1)
                        val vw = t.getDouble("viewportWidth")
                        val left = t.getDouble("columnLeft"); val width = t.getDouble("columnWidth")
                        val want = vw * m / 100
                        Log.i("EPR", "C-082 $flow $m %: left=$left width=$width (want left $want, width ${vw - 2 * want})")
                        if (kotlin.math.abs(left - want) > 1.5) problems += "$flow $m %: text starts at $left px, want $want"
                        if (kotlin.math.abs(width - (vw - 2 * want)) > 2.0) problems += "$flow $m %: column $width px, want ${vw - 2 * want}"
                        val quote = (JSONTokener(sc.js("JSON.stringify(window.__readerTest.blockLeft(1, 'blockquote'))")).nextValue() as? String)?.toDoubleOrNull()
                        if (quote == null || quote < left + 10) problems += "$flow $m %: blockquote indent lost (at $quote, text at $left)"
                    }
                }
                assertEquals(emptyList<String>(), problems)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.stop()
            }
        }
    }

    /**
     * A book whose CSS puts horizontal margins and padding on html, body and on a wrapper around all content, with a
     * title page (section 0) that is one narrow centred div (C-088) before the text chapter (section 1).
     */
    private fun marginsBook(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(java.util.zip.ZipEntry("mimetype").apply { method = java.util.zip.ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = java.util.zip.CRC32().apply { update(mt) }.value })
            z.write(mt); z.closeEntry()
            fun put(name: String, text: String) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:c0820000-0000-4000-8000-000000000082</dc:identifier><dc:title>Margins test</dc:title><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="book.css" media-type="text/css"/><item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c0"/><itemref idref="c1"/></spine></package>""")
            put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">One</a></li></ol></nav></body></html>""")
            put("OEBPS/book.css", "html { margin: 0 2em; padding: 0 1em } body { margin: 0 3em; padding: 0 20px } div.main { margin: 0 5%; padding: 0 1em } section { padding-left: 12px; margin-right: 8px } blockquote { margin: 1em 2em } div.title { width: 60%; margin: 0 auto; padding: 0 1em; text-align: center }")
            put("OEBPS/c0.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Title</title><link rel="stylesheet" href="book.css"/></head><body><div class="title"><h1>Margins test</h1><p>A narrow centred title page</p></div></body></html>""")
            val paras = (1..12).joinToString("") { "<p>Paragraph $it. " + "The quick brown fox jumps over the lazy dog and keeps running along the river bank. ".repeat(6) + "</p>" }
            put("OEBPS/c1.xhtml", """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title><link rel="stylesheet" href="book.css"/></head><body><div class="main"><section><h1>One</h1><blockquote><p>An indented quotation that must keep its indent.</p></blockquote>$paras</section></div></body></html>""")
        }
        return out.toByteArray()
    }
}
