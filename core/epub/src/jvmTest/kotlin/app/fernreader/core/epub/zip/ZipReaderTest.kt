package app.fernreader.core.epub.zip

import app.fernreader.core.epub.Corpus
import app.fernreader.core.epub.b
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.zipOf
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

    // ---- fix round 1 ----
    private fun le(vararg parts: Pair<Int, Long>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((n, v) in parts) for (k in 0 until n) out.write(((v ushr (8 * k)) and 0xff).toInt())
        return out.toByteArray()
    }

    /** One stored entry "a.txt" with 32-bit fields set to 0xFFFFFFFF and the real values in a zip64 extra field. */
    private fun handBuiltZip(data: ByteArray, extraCsize: Long, extraUsize: Long, extraLho: Long, plainEocd: Boolean = true): ByteArray {
        val name = "a.txt".b()
        val local = le(4 to 0x04034b50L, 2 to 10, 2 to 0, 2 to 0, 2 to 0, 2 to 0, 4 to 0, 4 to data.size.toLong(), 4 to data.size.toLong(),
            2 to name.size.toLong(), 2 to 0) + name + data
        val extra = le(2 to 1, 2 to 24, 8 to extraUsize, 8 to extraCsize, 8 to extraLho)
        val cd = le(4 to 0x02014b50L, 2 to 45, 2 to 45, 2 to 0, 2 to 0, 2 to 0, 2 to 0, 4 to 0, 4 to 0xFFFFFFFFL, 4 to 0xFFFFFFFFL,
            2 to name.size.toLong(), 2 to extra.size.toLong(), 2 to 0, 2 to 0, 2 to 0, 4 to 0, 4 to 0xFFFFFFFFL) + name + extra
        val eocd = le(4 to 0x06054b50L, 2 to 0, 2 to 0, 2 to 1, 2 to 1, 4 to cd.size.toLong(), 4 to local.size.toLong(), 2 to 0)
        return local + cd + eocd
    }

    @Test fun zip64_per_entry_extra_field() {
        val data = "hello zip64".b()
        val z = open(handBuiltZip(data, data.size.toLong(), data.size.toLong(), 0))
        val e = z.entries.single()
        assertEquals(data.size.toLong(), e.size)
        assertContentEquals(data, z.readBytes(e))
    }

    @Test fun zip64_high_bit_values_are_damaged_not_crashes() {
        val data = "hello".b()
        val neg = Long.MIN_VALUE or 5
        for ((c, u, l) in listOf(Triple(neg, 5L, 0L), Triple(5L, neg, 0L), Triple(5L, 5L, neg), Triple(5L, 5L, 1L shl 40))) {
            val e = assertFailsWith<ZipFormatException> { open(handBuiltZip(data, c, u, l)) }
            assertEquals(ZipError.DAMAGED, e.error)
        }
    }

    @Test fun zip64_negative_entry_count_is_damaged() {
        val rec = le(4 to 0x06064b50L, 8 to 44, 2 to 45, 2 to 45, 4 to 0, 4 to 0, 8 to (Long.MIN_VALUE or 1), 8 to 1, 8 to 0, 8 to 0)
        val loc = le(4 to 0x07064b50L, 4 to 0, 8 to 0, 4 to 1)
        val eocd = le(4 to 0x06054b50L, 2 to 0, 2 to 0, 2 to 0xFFFF, 2 to 0xFFFF, 4 to 0, 4 to 0xFFFFFFFFL, 2 to 0)
        val bytes = rec + loc + eocd
        assertEquals(ZipError.DAMAGED, assertFailsWith<ZipFormatException> { open(bytes) }.error)
    }

    @Test fun read_bytes_limit_declared_size() {
        val z = open(zipOf("big.bin" to ByteArray(1000) { 7 }))
        assertEquals(ZipError.DAMAGED, assertFailsWith<ZipFormatException> { z.readBytes(z.entry("big.bin")!!, 999) }.error)
        assertEquals(1000, z.readBytes(z.entry("big.bin")!!, 1000).size)
    }

    @Test fun read_bytes_limit_with_lying_header() {
        val raw = zipOf("big.bin" to ByteArray(100_000) { 7 })
        val z0 = open(raw)
        val real = z0.entry("big.bin")!!
        // Patch the central-directory uncompressed size (offset 24 in the CD header) to a small lie.
        var p = raw.size - 22
        val cdOff = ZipReader.u32(raw, p + 16).toInt()
        val lied = raw.copyOf()
        lied[cdOff + 24] = 10; lied[cdOff + 25] = 0; lied[cdOff + 26] = 0; lied[cdOff + 27] = 0
        val z = open(lied)
        assertEquals(10L, z.entries.single().size)
        assertEquals(ZipError.DAMAGED, assertFailsWith<ZipFormatException> { z.readBytes(z.entries.single(), 5_000) }.error)
        assertEquals(100_000, real.size.toInt())
    }

    @Test fun corrupt_deflate_data_is_damaged() {
        val raw = zipOf("a.bin" to ByteArray(50_000) { (it * 31).toByte() })
        val start = 30 + "a.bin".length
        val bad = raw.copyOf()
        for (k in start until start + 200) bad[k] = 0xFF.toByte()
        val z = open(bad)
        assertEquals(ZipError.DAMAGED, assertFailsWith<ZipFormatException> { z.readBytes(z.entries.single()) }.error)
    }

    @Test fun read_all_bytes_boundary() {
        for (n in listOf(8191, 8192, 8193, 20_000)) {
            val data = ByteArray(n) { 1 }
            val z = open(zipOf("x" to data, stored = setOf("x")))
            val e = z.entry("x")!!
            assertEquals(n, z.openStream(e).use { it.readAllBytes(n.toLong()) }.size)
            assertFailsWith<ZipFormatException> { z.openStream(e).use { it.readAllBytes(n - 1L) } }
        }
    }

    @Test fun exactly_65535_entries_without_zip64() {
        val out = java.io.ByteArrayOutputStream()
        val cd = java.io.ByteArrayOutputStream()
        for (k in 0 until 65_535) {
            val name = "e$k".b()
            val off = out.size().toLong()
            out.write(le(4 to 0x04034b50L, 2 to 10, 2 to 0, 2 to 0, 2 to 0, 2 to 0, 4 to 0, 4 to 0, 4 to 0, 2 to name.size.toLong(), 2 to 0)); out.write(name)
            cd.write(le(4 to 0x02014b50L, 2 to 10, 2 to 10, 2 to 0, 2 to 0, 2 to 0, 2 to 0, 4 to 0, 4 to 0, 4 to 0,
                2 to name.size.toLong(), 2 to 0, 2 to 0, 2 to 0, 2 to 0, 4 to 0, 4 to off)); cd.write(name)
        }
        val cdOff = out.size().toLong()
        out.write(cd.toByteArray())
        out.write(le(4 to 0x06054b50L, 2 to 0, 2 to 0, 2 to 0xFFFF, 2 to 0xFFFF, 4 to cd.size().toLong(), 4 to cdOff, 2 to 0))
        assertEquals(65_535, open(out.toByteArray()).entries.size)
    }
}
