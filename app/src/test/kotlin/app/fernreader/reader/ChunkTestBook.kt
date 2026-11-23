package app.fernreader.reader

import app.fernreader.core.epub.chunk.ChunkPolicy
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A small book whose OPS/big.xhtml splits under [small] (M8 8.3 wiring tests); OPS/a.xhtml stays whole. */
object ChunkTestBook {
    val small = ChunkPolicy(thresholdBytes = 20_000, targetChars = 10_000)
    /** A policy under which nothing in [bytes] is split (an unchunked control). */
    val never = ChunkPolicy(thresholdBytes = Int.MAX_VALUE)

    /** [extra]: more zip entries (not in the manifest or spine unless [extraManifest] lists them). */
    fun bytes(paragraphs: Int = 600, extra: Map<String, String> = emptyMap(), extraManifest: String = ""): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            // A fixed entry time: the same arguments give the same bytes (the same central-directory digest), so a
            // second call is "the same file" for the chunk cache even across a 2-second DOS time tick.
            fun put(name: String, data: String) { z.putNextEntry(ZipEntry(name).apply { time = 315_532_800_000L }); z.write(data.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OPS/p.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">chunk-test</dc:identifier><dc:title>Chunk</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="big" href="big.xhtml" media-type="application/xhtml+xml"/>$extraManifest</manifest><spine><itemref idref="a"/><itemref idref="big"/></spine></package>""")
            put("OPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>c</title></head><body><nav epub:type="toc"><ol><li><a href="a.xhtml">A</a></li><li><a href="big.xhtml#p500">P500</a></li></ol></nav></body></html>""")
            put("OPS/a.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>a</title></head><body><p>Start <a href="big.xhtml#p300">to 300</a></p></body></html>""")
            val paras = (0 until paragraphs).joinToString("\n") { "<p id=\"p$it\">Paragraph $it ${"lorem ipsum ".repeat(20)}</p>" }
            for ((n, t) in extra) put(n, t)
            put("OPS/big.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>B</title></head><body>\n$paras\n</body></html>")
        }
    }.toByteArray()
}
