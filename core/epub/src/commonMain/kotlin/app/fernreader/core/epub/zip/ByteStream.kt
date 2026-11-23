package app.fernreader.core.epub.zip

/** Minimal sequential stream (KMP-friendly replacement for InputStream). */
interface ByteStream : AutoCloseable {
    /** Reads up to [len] bytes; returns the count or -1 at the end. */
    fun read(dst: ByteArray, off: Int, len: Int): Int
}

fun ByteStream.readAllBytes(limit: Long): ByteArray {
    var buf = ByteArray(8192)
    var n = 0
    while (true) {
        if (n == buf.size) {
            if (buf.size.toLong() > limit) throw ZipFormatException(ZipError.DAMAGED, "entry exceeds $limit bytes")
            buf = buf.copyOf(minOf(buf.size.toLong() * 2, limit + 1).toInt())
        }
        val r = read(buf, n, buf.size - n)
        if (r < 0) return buf.copyOf(n)
        n += r
        if (n > limit) throw ZipFormatException(ZipError.DAMAGED, "entry exceeds $limit bytes")
    }
}

/** [length] bytes of [src] starting at [start]. */
class RangeStream(private val src: RandomAccessSource, start: Long, length: Long) : ByteStream {
    private var pos = start
    private val end = start + length
    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= end) return -1
        val n = src.read(pos, dst, off, minOf(len.toLong(), end - pos).toInt())
        if (n <= 0) throw ZipFormatException(ZipError.DAMAGED, "unexpected end of entry data")
        pos += n
        return n
    }
    override fun close() {}
}
