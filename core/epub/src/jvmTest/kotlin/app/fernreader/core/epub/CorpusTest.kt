package app.fernreader.core.epub

import app.fernreader.core.epub.meta.BookIdentity
import app.fernreader.core.epub.pkg.FailReason
import app.fernreader.core.epub.pkg.LoadResult
import app.fernreader.core.epub.pkg.PackageLoader
import app.fernreader.core.epub.pkg.WarningKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

object ExpectedCorpus {
    val failures = mapOf(
        "malformed-not-a-zip.epub" to FailReason.NOT_A_ZIP,
        "malformed-truncated.epub" to FailReason.DAMAGED_ARCHIVE,
        "malformed-broken-opf.epub" to FailReason.BROKEN_PACKAGE,
        "malformed-no-container.epub" to FailReason.NO_PACKAGE,
        "malformed-empty-spine.epub" to FailReason.NOTHING_READABLE,
        "fake.epub" to FailReason.NOT_A_ZIP,
    )
}

class CorpusTest {
    private fun load(f: File): Pair<LoadResult, String?> = openFileSource(f.path).use { src ->
        val (zip, r) = PackageLoader.open(src)
        r to (r as? LoadResult.Ok)?.let { BookIdentity.of(it.pkg.uniqueId, zip!!.centralDirectoryDigest) }
    }

    private fun check(files: List<File>) {
        for (f in files) {
            val t0 = System.nanoTime()
            val (r, _) = load(f)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val expected = ExpectedCorpus.failures[f.name]
            if (expected != null) assertEquals(expected, (r as? LoadResult.Failed)?.reason, f.name)
            else assertTrue(r is LoadResult.Ok, "${f.name}: $r")
            if (f.name == "malformed-missing-files-bad-xml.epub") { // R-001: partially damaged books are not failures
                val kinds = (r as LoadResult.Ok).warnings.map { it.kind }.toSet()
                assertTrue(kinds.containsAll(listOf(WarningKind.MISSING_FILE, WarningKind.UNKNOWN_IDREF)), "$kinds")
            }
            println("corpus ${f.name} ${if (r is LoadResult.Ok) "OK" else (r as LoadResult.Failed).reason} ${ms}ms")
        }
    }

    @Test fun generated_and_folder_books() =
        check((File(Corpus.dir, "generated").listFiles()!! + File(Corpus.dir, "folders").listFiles()!!).filter { it.name.endsWith(".epub") }.sortedBy { it.name })

    @Test fun downloaded_corpus_when_present() {
        val full = Corpus.full ?: return println("SKIP: downloaded corpus not present (tools/corpus.sh)")
        check(listOf("gutenberg", "epub3-samples").flatMap { File(full, it).listFiles()!!.toList() }.filter { it.name.endsWith(".epub") }.sortedBy { it.name })
        val p3 = load(File(full, "gutenberg/pride-and-prejudice-epub3-images.epub")).second
        val p2 = load(File(full, "gutenberg/pride-and-prejudice-epub2-noimages.epub")).second
        assertNotEquals(p3, p2) // F-015: different editions stay two entries
    }
}
