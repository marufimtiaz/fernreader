package app.epubreader.core.epub.pkg

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.b
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zipOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackageLoaderTest {
    private fun corpus(name: String) = openFileSource(Corpus.generated(name).path).use { PackageLoader.open(it).second }
    private fun reason(r: LoadResult) = (r as? LoadResult.Failed)?.reason

    @Test fun not_a_zip() = assertEquals(FailReason.NOT_A_ZIP, reason(corpus("malformed-not-a-zip.epub")))
    @Test fun truncated() = assertEquals(FailReason.DAMAGED_ARCHIVE, reason(corpus("malformed-truncated.epub")))
    @Test fun broken_opf() = assertEquals(FailReason.BROKEN_PACKAGE, reason(corpus("malformed-broken-opf.epub")))
    @Test fun no_container_and_no_opf() = assertEquals(FailReason.NO_PACKAGE, reason(corpus("malformed-no-container.epub")))
    @Test fun empty_spine() = assertEquals(FailReason.NOTHING_READABLE, reason(corpus("malformed-empty-spine.epub")))

    @Test fun partially_damaged_book_opens_with_warnings() {
        val r = corpus("malformed-missing-files-bad-xml.epub") as LoadResult.Ok
        assertEquals(listOf("c1", "c2", "c3", "c4"), r.pkg.spine.map { it.idref })          // ghost skipped
        assertEquals(listOf(true, true, false, true), r.pkg.spine.map { it.present })        // c3 file missing
        val kinds = r.warnings.map { it.kind }.toSet()
        assertTrue(WarningKind.MISSING_FILE in kinds && WarningKind.UNKNOWN_IDREF in kinds, "$kinds")
        assertEquals("Malformed & Broken", r.pkg.metadata.title)
    }

    @Test fun container_missing_but_single_opf_is_used() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">x</dc:identifier><dc:title>T</dc:title></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
        val zip = zipOf("mimetype" to "application/epub+zip".b(), "OPS/book.opf" to opf.b(), "OPS/c.xhtml" to "<html><body>x</body></html>".b())
        val r = PackageLoader.open(ByteArraySource(zip)).second as LoadResult.Ok
        assertEquals("OPS/book.opf", r.pkg.opfPath)
        assertTrue(r.warnings.any { it.kind == WarningKind.OPF_FOUND_BY_SEARCH })
        assertEquals("OPS/c.xhtml", r.pkg.spine.single().path)
    }

    @Test fun epub2_ncx_only_and_fixed_layout_flags() {
        val r = corpus("epub2-ncx-only.epub") as LoadResult.Ok
        assertEquals("2.0", r.pkg.version); assertEquals(20, r.pkg.spine.size); assertEquals(false, r.pkg.fixedLayout)
    }

    @Test fun all_good_generated_books_load() {
        for (n in listOf("stress-huge-single-chapter.epub", "stress-2000-chapters.epub", "stress-200-images.epub", "no-cover.epub", "footnotes-links.epub"))
            assertTrue(corpus(n) is LoadResult.Ok, n)
    }
    // Review Focus 1: the identifier text is whitespace-collapsed exactly like the reader (foliate-js) does it, so a
    // book read through the picker in M2 gets the same identity when the scanner finds its file.
    @Test fun unique_identifier_whitespace_is_collapsed_like_the_reader() {
        val opf = "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"other\">first</dc:identifier>" +
            "<dc:identifier id=\"uid\">\n        urn:uuid:1234\n        \t abcd </dc:identifier><dc:title>T</dc:title></metadata>" +
            "<manifest><item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c\"/></spine></package>"
        val container = "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles>" +
            "<rootfile full-path=\"OPS/p.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        val zip = zipOf("mimetype" to "application/epub+zip".b(), "META-INF/container.xml" to container.b(), "OPS/p.opf" to opf.b(), "OPS/c.xhtml" to "<html><body>x</body></html>".b())
        val r = PackageLoader.open(ByteArraySource(zip)).second as LoadResult.Ok
        assertEquals("urn:uuid:1234 abcd", r.pkg.uniqueId)
    }

    @Test fun container_pointing_to_a_missing_package_is_no_package() {
        val container = "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles>" +
            "<rootfile full-path=\"OPS/gone.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        val zip = zipOf("mimetype" to "application/epub+zip".b(), "META-INF/container.xml" to container.b())
        assertEquals(FailReason.NO_PACKAGE, reason(PackageLoader.open(ByteArraySource(zip)).second))
    }
}
