package app.fernreader.core.epub.meta

import app.fernreader.core.epub.Corpus
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.pkg.LoadResult
import app.fernreader.core.epub.pkg.PackageLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityTest {
    private val d1 = "a".repeat(64); private val d2 = "b".repeat(64)

    @Test fun format() = assertEquals(49, BookIdentity.of("urn:uuid:1", d1).length) // 16 + '-' + 32
    @Test fun same_inputs_same_identity() = assertEquals(BookIdentity.of("x", d1), BookIdentity.of("x", d1))
    @Test fun identifier_or_digest_change_identity() {
        assertNotEquals(BookIdentity.of("x", d1), BookIdentity.of("y", d1))
        assertNotEquals(BookIdentity.of("x", d1), BookIdentity.of("x", d2))
    }
    @Test fun whitespace_is_collapsed_and_trimmed() =
        assertEquals(BookIdentity.of("a b", d1), BookIdentity.of("  a \n b ", d1))

    /** foliate reports a missing identifier as "" or null; both must give the same identity. */
    @Test fun null_and_empty_identifier_are_equal() = assertEquals(BookIdentity.of(null, d1), BookIdentity.of("", d1))

    private fun id(f: File) = openFileSource(f.path).use { src ->
        val (zip, r) = PackageLoader.open(src)
        BookIdentity.of((r as LoadResult.Ok).pkg.uniqueId, zip!!.centralDirectoryDigest)
    }

    @Test fun byte_identical_copies_have_equal_identity() {
        val a = Corpus.folder("dup.epub"); val b = File.createTempFile("dup-copy", ".epub").also { a.copyTo(it, overwrite = true); it.deleteOnExit() }
        assertEquals(id(a), id(b))
    }

    @Test fun different_books_differ() = assertNotEquals(id(Corpus.folder("download.epub")), id(Corpus.folder("documents.epub")))

    // F-015: two editions with the same title stay two books (downloaded corpus only).
    @Test fun pp_epub2_vs_epub3_are_two_books() {
        val full = Corpus.full ?: return println("SKIP: downloaded corpus not present")
        assertNotEquals(id(File(full, "gutenberg/pride-and-prejudice-epub3-images.epub")), id(File(full, "gutenberg/pride-and-prejudice-epub2-noimages.epub")))
    }
}
