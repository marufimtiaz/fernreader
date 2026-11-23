package app.fernreader.core.epub.zip

/** Random-access bytes (a file, a file descriptor, or memory). Implementations must allow concurrent reads. */
interface RandomAccessSource : AutoCloseable {
    val size: Long
    /** Reads up to [len] bytes at [position] into [dst]; returns the count, or -1 at end of data. */
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int
}

fun RandomAccessSource.readFully(position: Long, len: Int): ByteArray {
    val out = ByteArray(len)
    var done = 0
    while (done < len) {
        val n = read(position + done, out, done, len - done)
        if (n <= 0) throw ZipFormatException(ZipError.DAMAGED, "unexpected end of data at ${position + done}")
        done += n
    }
    return out
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(len.toLong(), bytes.size - position).toInt()
        bytes.copyInto(dst, off, position.toInt(), position.toInt() + n)
        return n
    }
    override fun close() {}
}
