package app.epubreader.core.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Folder trees for scanner tests, built from the generated folder books (corpus.md §4). */
object ScanFixtures {
    private val corpus = File(System.getProperty("corpus.dir") ?: error("corpus.dir not set; run through Gradle"))
    fun book(name: String) = File(corpus, "folders/$name").also { require(it.exists()) { "missing $it" } }
    val full: File? = System.getProperty("fullcorpus.dir")?.let(::File)?.takeIf { File(it, "gutenberg").isDirectory }

    /** The corpus.md §4 placement under a fresh root (livetest is not placed: tests add it). */
    fun folderSet(): File {
        val root = Files.createTempDirectory("storage").toFile()
        fun put(src: String, dst: String) = book(src).copyTo(File(root, dst))
        listOf("download", "dup", "renamed", "moved").forEach { put("$it.epub", "Download/$it.epub") }
        put("fake.epub", "Download/fake.epub")
        put("upper.epub", "Download/UPPER.EPUB")
        put("documents.epub", "Documents/Books/documents.epub")
        put("dup.epub", "Documents/Books/dup-copy.epub")
        put("nested3.epub", "Books/Fiction/Classics/Old/nested3.epub")
        put("unicode.epub", "Livres Été 2026/unicode.epub")
        put("hidden.epub", ".hidden/hidden.epub")
        put("nomedia.epub", "NoMedia/nomedia.epub"); File(root, "NoMedia/.nomedia").writeText("")
        put("androidmedia.epub", "Android/media/com.example.test/androidmedia.epub")
        put("deletedfolder.epub", "Books/ToDelete/deletedfolder.epub")
        put("insidecustom.epub", "Books/insidecustom.epub")
        put("outsidecustom.epub", "Download/outsidecustom.epub")
        return root
    }

    /** A minimal valid EPUB with the given identifier text (written as is, whitespace included) and title. */
    fun epub(identifier: String, title: String, extra: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(n: String, t: String) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OPS/p.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$identifier</dc:identifier><dc:title>$title</dc:title><dc:creator>Test Author</dc:creator></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""")
            put("OPS/c.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>text</p></body></html>")
            if (extra != null) put("OPS/extra.txt", extra)
        }
        return out.toByteArray()
    }
}

/** A walker that emits a fixed list, suspending before item [pauseAt] until [release] completes. */
class ScriptedWalker(override val root: String, private val files: List<FoundFile>, private val pauseAt: Int = -1,
                     private val release: kotlinx.coroutines.CompletableDeferred<Unit>? = null) : StorageWalker {
    override suspend fun walk(scope: ScanScope, emit: suspend (FoundFile) -> Unit) {
        files.forEachIndexed { i, f -> if (i == pauseAt) release!!.await(); emit(f) }
    }
}

fun found(f: File) = FoundFile(f.path, f.parent, f.length(), f.lastModified())
