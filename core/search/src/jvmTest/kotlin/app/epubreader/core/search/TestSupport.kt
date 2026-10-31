package app.epubreader.core.search

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.text.ExtractedText
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Generated corpus (always present through Gradle) and the optional downloaded corpus. */
object Corpus {
    val dir = File(System.getProperty("corpus.dir") ?: error("corpus.dir not set; run through Gradle"))
    fun generated(name: String) = File(dir, "generated/$name").also { require(it.exists()) { "missing $it" } }
    /** Null when the full corpus is not downloaded: tests that need it skip. */
    fun full(path: String): File? = System.getProperty("fullcorpus.dir")?.let { File(it, path) }?.takeIf { it.exists() }
}

fun openBook(file: File): ZipBookText = ZipBookText(ZipReader.open(openFileSource(file.path)))

/** A tiny EPUB whose spine items are [sections] (each the inner XHTML of `<body>`), in order. */
fun miniEpub(sections: List<String>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        fun put(name: String, data: String) { z.putNextEntry(ZipEntry(name)); z.write(data.toByteArray()); z.closeEntry() }
        put("mimetype", "application/epub+zip")
        put("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        val manifest = sections.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
        val spine = sections.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        put("OPS/p.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">mini</dc:identifier><dc:title>Mini</dc:title></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>""")
        sections.forEachIndexed { i, body ->
            put("OPS/c$i.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c$i</title></head><body>$body</body></html>""")
        }
    }
    return out.toByteArray()
}

fun miniBook(sections: List<String>): ZipBookText = ZipBookText(ZipReader.open(ByteArraySource(miniEpub(sections))))

/** Markup strings as sections; [onSection] runs before each is returned (to cancel, fail, or gate). */
class ListBookText(private val sections: List<String?>, private val onSection: (Int) -> Unit = {}) : BookText {
    val reads = mutableListOf<Int>()
    override val sectionCount get() = sections.size
    override fun section(index: Int): ExtractedText? { reads += index; onSection(index); return sections[index]?.let { TextExtractor.extract(it) } }
}

fun rowCount(dbPath: String): Long {
    val c = BundledSQLiteDriver().open(dbPath)
    try { return c.prepare("SELECT count(*) FROM seg").use { it.step(); it.getLong(0) } } finally { c.close() }
}

fun allRows(dbPath: String): List<Triple<Int, Int, String>> {
    val c = BundledSQLiteDriver().open(dbPath)
    try {
        return c.prepare("SELECT spine, start, text FROM seg ORDER BY rowid").use { s ->
            buildList { while (s.step()) add(Triple(s.getLong(0).toInt(), s.getLong(1).toInt(), s.getText(2))) }
        }
    } finally { c.close() }
}

/** Lowercase, diacritics stripped: how the index compares words. */
fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
