package app.fernreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.Flow
import app.fernreader.core.data.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * C-081: a cover page made of an inline SVG wrapping one image (`width/height="100%"`, lowercase `viewbox` and
 * `preserveaspectratio`) shows the image whole on one screen, aspect ratio kept, with a plain SVG 2 `href` and with
 * `xlink:href`, in paged and scrolled flow, and also when the SVG sits deep inside a chapter that is chunked (> 512 KB).
 * A synthetic book built here (no user book).
 */
@RunWith(AndroidJUnit4::class)
class SvgCoverTest {
    private val svgNs = "http://www.w3.org/2000/svg"

    private fun svg(ref: String) = """<svg xmlns="$svgNs" width="100%" height="100%" viewbox="0 0 900 1129" preserveaspectratio="xMidYMid meet"><image width="900" height="1129" $ref="cover.jpeg"/></svg>"""

    private fun book(): ByteArray {
        val jpeg = ByteArrayOutputStream().also { o ->
            Bitmap.createBitmap(900, 1129, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 90, 70)) }.compress(Bitmap.CompressFormat.JPEG, 80, o)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mt) }.value })
            z.write(mt); z.closeEntry()
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            fun page(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink"><head><title>$title</title></head><body>$body</body></html>""".toByteArray()
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            val docs = listOf("cover-href", "cover-xlink", "text", "huge")
            put("OEBPS/content.opf", ("""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:c0810000-0000-4000-8000-000000000081</dc:identifier><dc:title>SVG cover test</dc:title><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="img" href="cover.jpeg" media-type="image/jpeg"/>""" +
                docs.joinToString("") { """<item id="$it" href="$it.xhtml" media-type="application/xhtml+xml" properties="svg"/>""" } +
                """</manifest><spine>""" + docs.joinToString("") { """<itemref idref="$it"/>""" } + "</spine></package>").toByteArray())
            put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${docs.joinToString("") { """<li><a href="$it.xhtml">$it</a></li>""" }}</ol></nav></body></html>""".toByteArray())
            put("OEBPS/cover.jpeg", jpeg)
            put("OEBPS/cover-href.xhtml", page("Cover", "<div>${svg("href")}</div>"))
            put("OEBPS/cover-xlink.xhtml", page("Cover", "<div>${svg("xlink:href")}</div>"))
            put("OEBPS/text.xhtml", page("Text", (1..30).joinToString("") { "<p>Paragraph $it. " + "The quick brown fox jumps over the lazy dog. ".repeat(5) + "</p>" }))
            // > 512 KB: chunked into parts; the SVG figure sits in the middle, so it lands in a later part
            val para = (1..1300).map { "<p>Long chapter paragraph $it. " + "Words to make this chapter large enough for chunking. ".repeat(8) + "</p>" }
            put("OEBPS/huge.xhtml", page("Huge", (para.subList(0, 650) + "<div id=\"fig\">${svg("href")}</div>" + para.subList(650, para.size)).joinToString("")))
        }
        return out.toByteArray()
    }

    /** The SVG image in the loaded section [index]: href form, decoded size, its box and the SVG's box in host px. */
    private suspend fun ReaderHarness.probe(index: Int): JSONObject? {
        sc.js("window.__readerTest.svgImage($index)")
        repeat(40) {
            delay(100)
            val v = JSONTokener(sc.js("JSON.stringify(window.__svgImage)")).nextValue()
            if (v is String && v != "null") return JSONObject(v).takeUnless { it.has("none") }
        }
        return null
    }

    @Test fun svg_cover_pages_show_the_whole_image_in_both_flows(): Unit = runBlocking {
        warAndPeace() // storage access
        val file = TestBooks.write(InstrumentationRegistry.getInstrumentation().targetContext, "c081-svg-cover.epub", book())
        val problems = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(file, withToc = false)
                for (flow in listOf(Flow.PAGED, Flow.SCROLLED)) {
                    sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = flow) } }
                    h.still()
                    for ((index, name) in listOf(0 to "href", 1 to "xlink:href")) {
                        h.drain(); sc.js("window.__readerTest.goIndex($index)")
                        val at = h.relocate(10_000) { it.sectionIndex == index }
                        h.still()
                        val p = h.probe(index)
                        Log.i("EPR", "C-081 $flow $name: $p pages=${at.pages}")
                        check(problems, "$flow $name", p, if (flow == Flow.PAGED) at.pages else null)
                    }
                    // the chunked chapter: walk its parts until the one holding the figure is loaded
                    var found: JSONObject? = null
                    var index = 3
                    while (found == null && index < 40) {
                        h.drain(); sc.js("window.__readerTest.goIndex($index)")
                        runCatching { h.relocate(10_000) { it.sectionIndex == index } }.getOrNull() ?: break
                        h.still()
                        found = h.probe(index)
                        if (found == null) index++
                    }
                    Log.i("EPR", "C-081 $flow chunked part $index: $found")
                    if (found == null) problems += "$flow chunked: no SVG image found in the parts of the huge chapter"
                    else if (!found.getString("href").startsWith("blob:") || found.getInt("natural") <= 0) problems += "$flow chunked: image not loaded ($found)"
                }
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    private fun check(problems: MutableList<String>, what: String, p: JSONObject?, pages: Int?) {
        if (p == null) { problems += "$what: no SVG image in the section"; return }
        if (!p.getString("href").startsWith("blob:")) problems += "$what: href not rewritten (${p.getString("href")})"
        if (p.getInt("natural") <= 0) { problems += "$what: image did not load"; return }
        val vw = p.getDouble("vw"); val vh = p.getDouble("vh")
        val img = p.getJSONObject("img"); val svg = p.getJSONObject("svg")
        val w = img.getDouble("width"); val hgt = img.getDouble("height")
        if (w < vw * 0.5 && hgt < vh * 0.5) problems += "$what: picture too small (${w}x$hgt in ${vw}x$vh)"
        if (kotlin.math.abs(w / hgt - 900.0 / 1129) > 0.02) problems += "$what: aspect ratio not kept (${w}x$hgt)"
        val l = img.getDouble("left"); val t = img.getDouble("top")
        if (l < -1 || t < -1 || l + w > vw + 1 || t + hgt > vh + 1) problems += "$what: picture not whole on screen ($img in ${vw}x$vh; svg $svg)"
        if (pages != null && pages != 1) problems += "$what: the cover page takes $pages pages"
    }
}
