package app.epubreader.core.epub.zip

import app.epubreader.core.epub.inflateStream
import app.epubreader.core.epub.sha256Hex

enum class ZipError { NOT_A_ZIP, DAMAGED }

class ZipFormatException(val error: ZipError, message: String) : Exception(message)

class ZipEntry(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val crc32: Long,
    internal val localHeaderOffset: Long,
    val encrypted: Boolean,
) {
    val isDirectory: Boolean get() = name.endsWith('/')
}

/**
 * Zip reader working from the central directory (random access, nothing extracted up front).
 * [centralDirectoryDigest] is the SHA-256 of the raw central directory: byte-identical archives have equal
 * digests, and any change to an entry's name, size or CRC changes it. Used for book identity (F-015).
 */
class ZipReader private constructor(
    private val src: RandomAccessSource,
    val entries: List<ZipEntry>,
    val centralDirectoryDigest: String,
) : AutoCloseable {
    private val byName = HashMap<String, ZipEntry>(entries.size * 2).also { m -> for (e in entries) m.putIfAbsent(e.name, e) }
    private val byLower by lazy { HashMap<String, ZipEntry>().also { m -> for (e in entries) m.putIfAbsent(e.name.lowercase(), e) } }

    /** Exact name, then without a leading slash / with forward slashes, then case-insensitive. */
    fun entry(path: String): ZipEntry? {
        val p = path.replace('\\', '/').removePrefix("/")
        return byName[p] ?: byLower[p.lowercase()]
    }

    fun openStream(e: ZipEntry): ByteStream {
        if (e.encrypted) throw ZipFormatException(ZipError.DAMAGED, "encrypted entry ${e.name}")
        val lh = src.readFully(e.localHeaderOffset, 30)
        if (u32(lh, 0) != LOCAL_SIG) throw ZipFormatException(ZipError.DAMAGED, "bad local header for ${e.name}")
        val dataStart = e.localHeaderOffset + 30 + u16(lh, 26) + u16(lh, 28)
        if (dataStart + e.compressedSize > src.size) throw ZipFormatException(ZipError.DAMAGED, "entry ${e.name} beyond end of file")
        val raw = RangeStream(src, dataStart, e.compressedSize)
        return when (e.method) {
            0 -> raw
            8 -> inflateStream(raw)
            else -> throw ZipFormatException(ZipError.DAMAGED, "unsupported compression method ${e.method} for ${e.name}")
        }
    }

    fun readBytes(e: ZipEntry, limit: Long = DEFAULT_LIMIT): ByteArray {
        if (e.size > limit) throw ZipFormatException(ZipError.DAMAGED, "entry ${e.name} is ${e.size} bytes (limit $limit)")
        return openStream(e).use { it.readAllBytes(limit) }
    }

    override fun close() = src.close()

    companion object {
        const val DEFAULT_LIMIT = 64L shl 20
        private const val MAX_ENTRIES = 200_000L
        private const val MAX_CD_BYTES = 64L shl 20
        private const val LOCAL_SIG = 0x04034b50L
        private const val CEN_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val Z64_LOC_SIG = 0x07064b50L
        private const val Z64_EOCD_SIG = 0x06064b50L

        private fun damaged(msg: String) = ZipFormatException(ZipError.DAMAGED, msg)

        fun open(src: RandomAccessSource): ZipReader {
            val size = src.size
            if (size < 22) throw ZipFormatException(ZipError.NOT_A_ZIP, "file too small to be a zip archive")
            val tailLen = minOf(size, 22L + 65_535L).toInt()
            val tailPos = size - tailLen
            val tail = src.readFully(tailPos, tailLen)
            var i = tailLen - 22
            while (i >= 0 && u32(tail, i) != EOCD_SIG) i--
            if (i < 0) {
                val head = src.readFully(0, 4)
                throw if (u32(head, 0) == LOCAL_SIG) damaged("end of central directory not found (truncated archive?)")
                else ZipFormatException(ZipError.NOT_A_ZIP, "not a zip archive")
            }
            val eocdPos = tailPos + i
            var total = u16(tail, i + 10).toLong()
            var cdSize = u32(tail, i + 12)
            var cdOff = u32(tail, i + 16)
            if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
                if (eocdPos < 20) throw damaged("zip64 locator missing")
                val loc = src.readFully(eocdPos - 20, 20)
                if (u32(loc, 0) != Z64_LOC_SIG) throw damaged("zip64 locator missing")
                val recPos = u64(loc, 8)
                if (recPos < 0 || recPos + 56 > size) throw damaged("zip64 record out of range")
                val rec = src.readFully(recPos, 56)
                if (u32(rec, 0) != Z64_EOCD_SIG) throw damaged("bad zip64 end record")
                total = u64(rec, 32); cdSize = u64(rec, 40); cdOff = u64(rec, 48)
            }
            if (cdOff < 0 || cdSize < 0 || cdOff + cdSize > eocdPos) throw damaged("central directory out of range")
            if (total > MAX_ENTRIES || cdSize > MAX_CD_BYTES) throw damaged("archive too large ($total entries)")
            val cd = src.readFully(cdOff, cdSize.toInt())
            val entries = ArrayList<ZipEntry>(total.toInt())
            var p = 0
            while (p + 46 <= cd.size && entries.size < total) {
                if (u32(cd, p) != CEN_SIG) throw damaged("bad central directory header at $p")
                val flags = u16(cd, p + 8)
                val method = u16(cd, p + 10)
                val crc = u32(cd, p + 16)
                var csize = u32(cd, p + 20)
                var usize = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28)
                val extraLen = u16(cd, p + 30)
                val commentLen = u16(cd, p + 32)
                var lho = u32(cd, p + 42)
                val nameStart = p + 46
                val extraStart = nameStart + nameLen
                val extraEnd = extraStart + extraLen
                if (extraEnd + commentLen > cd.size) throw damaged("central directory entry overruns")
                val name = decodeName(cd, nameStart, nameLen, utf8Flag = flags and 0x800 != 0)
                var e = extraStart
                while (e + 4 <= extraEnd) {
                    val id = u16(cd, e); val len = u16(cd, e + 2); var q = e + 4; val fieldEnd = minOf(q + len, extraEnd)
                    if (id == 1) {
                        if (usize == 0xFFFFFFFFL && q + 8 <= fieldEnd) { usize = u64(cd, q); q += 8 }
                        if (csize == 0xFFFFFFFFL && q + 8 <= fieldEnd) { csize = u64(cd, q); q += 8 }
                        if (lho == 0xFFFFFFFFL && q + 8 <= fieldEnd) { lho = u64(cd, q) }
                    }
                    e += 4 + len
                }
                entries += ZipEntry(name, method, csize, usize, crc, lho, encrypted = flags and 1 != 0)
                p = extraEnd + commentLen
            }
            if (entries.size.toLong() != total) throw damaged("central directory lists ${entries.size} of $total entries")
            return ZipReader(src, entries, sha256Hex(cd))
        }

        private fun decodeName(b: ByteArray, off: Int, len: Int, utf8Flag: Boolean): String =
            if (utf8Flag) b.decodeToString(off, off + len)
            else runCatching { b.decodeToString(off, off + len, throwOnInvalidSequence = true) }.getOrElse { decodeCp437(b, off, len) }

        internal fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        internal fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
        internal fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)
    }
}
