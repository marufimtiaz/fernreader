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
        /** The nav document's toc is empty (a book without a table of contents). */
        noToc: Boolean = false,
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
            val toc = if (noToc) "" else (1..chapters).joinToString("") { """<li><a href="c$it.xhtml">Chapter $it</a></li>""" } +
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

    /** C-083 page sources of [pagesEpub]. */
    enum class Pages { NAV, NCX, PAGE_MAP, NONE }
    val ROMAN = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x", "xi", "xii")

    /**
     * C-083 fixtures: [chapters] chapters with two print page breaks each (`<span id="pgN" title="..."/>`, roman labels:
     * one at the chapter start, one before the middle paragraph), named by an EPUB 3 nav page-list, an EPUB 2 NCX
     * pageList, a page-map, or nothing (then the book has virtual pages).
     */
    fun pagesEpub(kind: Pages, chapters: Int = 3, paragraphs: Int = 60, id: String = "urn:uuid:pages-$kind-${System.nanoTime()}"): ByteArray {
        val out = ByteArrayOutputStream()
        val labels = (1..chapters * 2).map { n -> ROMAN.getOrElse(n - 1) { "p$n" } to "c${(n + 1) / 2}.xhtml#pg$n" }
        ZipOutputStream(out).use { z ->
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mt) }.value
            }); z.write(mt); z.closeEntry()
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val items = (1..chapters).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = (1..chapters).joinToString("") { """<itemref idref="c$it"/>""" }
            val map = if (kind == Pages.PAGE_MAP) """<item id="map" href="page-map.xml" media-type="application/oebps-page-map+xml"/>""" else ""
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="${if (kind == Pages.NCX) "2.0" else "3.0"}" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$id</dc:identifier><dc:title>Pages $kind</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>$map$items</manifest><spine toc="ncx"${if (kind == Pages.PAGE_MAP) " page-map=\"map\"" else ""}>$spine</spine></package>""")
            val toc = (1..chapters).joinToString("") { """<li><a href="c$it.xhtml">Chapter $it</a></li>""" }
            val pageList = if (kind == Pages.NAV) "<nav epub:type=\"page-list\" hidden=\"\"><ol>" + labels.joinToString("") { (l, h) -> "<li><a href=\"$h\">$l</a></li>" } + "</ol></nav>" else ""
            put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$toc</ol></nav>$pageList</body></html>""")
            val ncxPages = if (kind == Pages.NCX) "<pageList>" + labels.withIndex().joinToString("") { (i, p) ->
                "<pageTarget id=\"pt$i\" type=\"normal\" value=\"${i + 1}\" playOrder=\"${chapters + i + 1}\"><navLabel><text>${p.first}</text></navLabel><content src=\"${p.second}\"/></pageTarget>"
            } + "</pageList>" else ""
            put("OEBPS/toc.ncx", """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Pages</text></docTitle><navMap>${(1..chapters).joinToString("") { "<navPoint id=\"n$it\" playOrder=\"$it\"><navLabel><text>Chapter $it</text></navLabel><content src=\"c$it.xhtml\"/></navPoint>" }}</navMap>$ncxPages</ncx>""")
            if (kind == Pages.PAGE_MAP) put("OEBPS/page-map.xml", "<page-map xmlns=\"http://www.idpf.org/2007/opf\">" + labels.joinToString("") { (l, h) -> "<page name=\"$l\" href=\"$h\"/>" } + "</page-map>")
            for (c in 1..chapters) {
                fun brk(n: Int) = if (kind == Pages.NONE) "" else "<span id=\"pg$n\" title=\"${ROMAN.getOrElse(n - 1) { "p$n" }}\"/>"
                val first = (c - 1) * 2 + 1
                val body = (1..paragraphs).joinToString("\n") { p -> (if (p == paragraphs / 2 + 1) brk(first + 1) else "") + "<p>Chapter $c, paragraph $p. " + "Lorem ipsum dolor sit amet. ".repeat(6) + "</p>" }
                put("OEBPS/c$c.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $c</title></head><body>${brk(first)}<h1>Chapter $c</h1>
$body
</body></html>""")
            }
        }
        return out.toByteArray()
    }

    /**
     * C-087: a fixed-layout (pre-paginated) picture book of [pages] pages, one spine item each (a coloured page with a
     * caption); with [pageList] its nav carries a print page-list labelling them i, ii, iii, ...
     */
    fun fixedLayoutEpub(pages: Int = 4, pageList: Boolean = false, id: String = "urn:uuid:fxl-$pageList-${System.nanoTime()}"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mt) }.value
            }); z.write(mt); z.closeEntry()
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val items = (1..pages).joinToString("") { """<item id="p$it" href="p$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = (1..pages).joinToString("") { """<itemref idref="p$it"/>""" }
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$id</dc:identifier><dc:title>Picture book</dc:title><dc:language>en</dc:language><meta property="rendition:layout">pre-paginated</meta><meta property="rendition:spread">none</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$items</manifest><spine>$spine</spine></package>""")
            val list = if (!pageList) "" else "<nav epub:type=\"page-list\" hidden=\"\"><ol>" +
                (1..pages).joinToString("") { "<li><a href=\"p$it.xhtml\">${ROMAN[it - 1]}</a></li>" } + "</ol></nav>"
            put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="p1.xhtml">Start</a></li></ol></nav>$list</body></html>""")
            for (p in 1..pages) {
                put("OEBPS/p$p.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page $p</title><meta name="viewport" content="width=600, height=800"/></head><body style="margin:0;width:600px;height:800px;background:hsl(${p * 70}, 50%, 70%)"><p style="font-size:48px;text-align:center;padding-top:300px">Picture $p</p></body></html>""")
            }
        }
        return out.toByteArray()
    }
}
