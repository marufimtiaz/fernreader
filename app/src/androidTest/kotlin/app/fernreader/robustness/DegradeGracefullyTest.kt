package app.fernreader.robustness

import androidx.test.core.app.ActivityScenario
import app.fernreader.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.TestBooks
import app.fernreader.reader.ReaderHarness
import app.fernreader.reader.hook
import app.fernreader.reader.js
import kotlinx.coroutines.runBlocking
import org.json.JSONTokener
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R-007: scripts in content do not run; a broken font falls back to the default font; a broken stylesheet is
 * ignored; unsupported media leave the text readable. The book still opens, lays out and turns pages.
 */
@RunWith(AndroidJUnit4::class)
class DegradeGracefullyTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun hostileBook(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mt) }.value })
            z.write(mt); z.closeEntry()
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            fun put(name: String, text: String) = put(name, text.toByteArray())
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:uuid:r7-hostile</dc:identifier><dc:title>R7 Hostile</dc:title><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml" properties="scripted"/><item id="css" href="broken.css" media-type="text/css"/><item id="font" href="broken.ttf" media-type="font/ttf"/><item id="vid" href="clip.mkv" media-type="video/x-matroska"/></manifest><spine><itemref idref="c1"/></spine></package>""")
            put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">One</a></li></ol></nav></body></html>""")
            put("OEBPS/broken.css", byteArrayOf(0x7b, 0x7b, 0x00, 0xff.toByte(), 0x3a) + "body { font-family: \"Broken Face\"; color: ".toByteArray() + "}}}} p { ;;; ".toByteArray())
            put("OEBPS/broken.ttf", ByteArray(4096) { (it * 31).toByte() })
            put("OEBPS/clip.mkv", ByteArray(1024) { 7 })
            val paras = (1..60).joinToString("") { "<p>Paragraph $it. " + "Lorem ipsum dolor sit amet. ".repeat(6) + "</p>" }
            put("OEBPS/c1.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title><link rel="stylesheet" type="text/css" href="broken.css"/><style>@font-face { font-family: "Broken Face"; src: url(broken.ttf); } h1 { font-family: "Broken Face"; }</style><script>document.addEventListener('DOMContentLoaded', function () { document.getElementById('flag').textContent = 'SCRIPTS RAN'; });</script></head><body onload="document.getElementById('flag').textContent='ONLOAD RAN'"><h1 id="flag">SCRIPTS ARE OFF</h1><video src="clip.mkv" controls="controls">Video not supported</video><object data="clip.mkv" type="video/x-matroska">Object fallback</object>$paras<script>document.getElementById('flag').textContent = 'INLINE RAN';</script></body></html>""")
        }
        return out.toByteArray()
    }

    @Test fun scripts_off_broken_font_and_stylesheet_ignored_book_still_reads(): Unit = runBlocking {
        val f = TestBooks.write(ctx, "r7-hostile.epub", hostileBook())
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            val first = h.open(f)
            assertNull("no error", h.a.controller.state.value.error)
            val heading = JSONTokener(sc.js("JSON.stringify(window.__readerTest.firstBlockText(0))")).nextValue().toString()
            assertTrue("scripts must not run: $heading", heading.contains("SCRIPTS ARE OFF"))
            // The heading asks for the broken face and the body for a broken stylesheet's rules: the text is still laid out.
            val pages = sc.hook("page()").getInt("textPages")
            assertTrue("laid out: $pages pages", pages >= 2)
            h.send(app.fernreader.reader.bridge.ToWeb.Next())
            val next = h.relocate { it.cfi != first.cfi }
            assertTrue("turns", next.cfi != first.cfi)
            h.stop()
        }
    }
}
