package app.epubreader.core.epub.zip

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.b
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.zipOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ZipReaderTest {
    private fun open(bytes: ByteArray) = ZipReader.open(ByteArraySource(bytes))

    @Test fun reads_stored_and_deflated_entries() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val z = open(zipOf("mimetype" to "application/epub+zip".b(), "OEBPS/big.bin" to big, stored = setOf("mimetype")))
        assertEquals("application/epub+zip", z.readBytes(z.entry("mimetype")!!).decodeToString())
        assertContentEquals(big, z.readBytes(z.entry("OEBPS/big.bin")!!))
    }

    @Test fun lookup_is_exact_then_case_insensitive() {
        val z = open(zipOf("OEBPS/Text/Ch1.xhtml" to "x".b()))
        assertNotNull(z.entry("OEBPS/Text/Ch1.xhtml"))
        assertNotNull(z.entry("oebps/text/ch1.xhtml"))
        assertNotNull(z.entry("/OEBPS/Text/Ch1.xhtml"))
    }

    @Test fun utf8_name_decoded() {
        val z = open(zipOf("Livres Été/ch.xhtml" to "x".b()))
        assertNotNull(z.entry("Livres Été/ch.xhtml"))
    }

    @Test fun cp437_name_decoded() {
        val z = open(zipOf("Été.xhtml" to "x".b(), charset = charset("Cp437")))
        assertEquals("Été.xhtml", z.entries.single().name)
    }

    @Test fun not_a_zip() {
        val e = assertFailsWith<ZipFormatException> { open("This file is plain text pretending to be an EPUB.\n".repeat(50).b()) }
        assertEquals(ZipError.NOT_A_ZIP, e.error)
    }

    @Test fun tiny_file_is_not_a_zip() {
        assertEquals(ZipError.NOT_A_ZIP, assertFailsWith<ZipFormatException> { open("not an epub at all\n".b()) }.error)
    }

    @Test fun truncated_zip_is_damaged() {
        val full = zipOf("a.txt" to ByteArray(50_000) { 1 }, "b.txt" to ByteArray(50_000) { 2 })
        val e = assertFailsWith<ZipFormatException> { open(full.copyOf(full.size * 2 / 3)) }
        assertEquals(ZipError.DAMAGED, e.error)
    }

    @Test fun digest_equal_for_identical_bytes_and_differs_on_change() {
        val a = zipOf("x.txt" to "one".b()); val b = a.copyOf(); val c = zipOf("x.txt" to "two".b())
        assertEquals(open(a).centralDirectoryDigest, open(b).centralDirectoryDigest)
        assertNotEquals(open(a).centralDirectoryDigest, open(c).centralDirectoryDigest)
    }

    @Test fun zip64_archive_with_many_entries() {
        val entries = (0 until 70_000).map { "e$it" to ByteArray(0) }.toTypedArray()
        val z = open(zipOf(*entries))
        assertEquals(70_000, z.entries.size)
    }

    @Test fun corpus_malformed_files_classified() {
        fun err(name: String) = assertFailsWith<ZipFormatException> {
            openFileSource(Corpus.generated(name).path).use { ZipReader.open(it) }
        }.error
        assertEquals(ZipError.NOT_A_ZIP, err("malformed-not-a-zip.epub"))
        assertEquals(ZipError.DAMAGED, err("malformed-truncated.epub"))
        assertEquals(ZipError.NOT_A_ZIP, assertFailsWith<ZipFormatException> {
            openFileSource(Corpus.folder("fake.epub").path).use { ZipReader.open(it) }
        }.error)
    }

    @Test fun file_source_reads_corpus_book() {
        openFileSource(Corpus.generated("stress-200-images.epub").path).use { src ->
            val z = ZipReader.open(src)
            assertNotNull(z.entry("META-INF/container.xml"))
            assertEquals(200, z.entries.count { it.name.endsWith(".png") && it.name.contains("/images/") })
        }
    }

    @Test fun cp437_table_has_128_chars() = assertEquals(128, CP437_HIGH.length)
}
