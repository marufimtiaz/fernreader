package app.epubreader

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small valid EPUB 3 books for instrumented tests (no corpus or storage permission needed). */
object TestBooks {
    fun epub(
        id: String = "urn:uuid:00000000-0000-4000-8000-000000000001",
        title: String = "Smoke Book",
        chapters: Int = 3,
        paragraphs: Int = 40,
        missingTocTarget: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mt) }.value
            }); z.write(mt); z.closeEntry()
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val items = (1..chapters).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = (1..chapters).joinToString("") { """<itemref idref="c$it"/>""" }
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$id</dc:identifier><dc:title>$title</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$items</manifest><spine>$spine</spine></package>""")
            val toc = (1..chapters).joinToString("") { """<li><a href="c$it.xhtml">Chapter $it</a></li>""" } +
                (if (missingTocTarget) """<li><a href="gone.xhtml">Missing chapter</a></li>""" else "")
            put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$toc</ol></nav></body></html>""")
            for (c in 1..chapters) {
                val body = (1..paragraphs).joinToString("") { "<p>Chapter $c, paragraph $it. " + "Lorem ipsum dolor sit amet. ".repeat(6) + "</p>" }
                put("OEBPS/c$c.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $c</title></head><body><h1>Chapter $c</h1>$body</body></html>""")
            }
        }
        return out.toByteArray()
    }

    fun write(ctx: Context, name: String, bytes: ByteArray = epub()): File =
        File(ctx.cacheDir, "testbooks").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) }
}
