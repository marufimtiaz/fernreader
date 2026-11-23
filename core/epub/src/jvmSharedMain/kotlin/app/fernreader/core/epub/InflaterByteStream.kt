package app.fernreader.core.epub

import app.fernreader.core.epub.zip.ByteStream
import app.fernreader.core.epub.zip.ZipError
import app.fernreader.core.epub.zip.ZipFormatException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class InflaterByteStream(private val raw: ByteStream) : ByteStream {
    private val inflater = Inflater(true)
    private val input = ByteArray(64 * 1024)
    private var eofIn = false

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            try {
                val n = inflater.inflate(dst, off, len)
                if (n > 0) return n
            } catch (e: DataFormatException) {
                throw ZipFormatException(ZipError.DAMAGED, "corrupt deflate data: ${e.message}")
            }
            if (inflater.finished() || inflater.needsDictionary()) return -1
            if (inflater.needsInput()) {
                if (eofIn) { // raw deflate may need one extra dummy byte at the end
                    if (inflater.finished()) return -1
                    throw ZipFormatException(ZipError.DAMAGED, "truncated deflate data")
                }
                val r = raw.read(input, 0, input.size)
                if (r < 0) { eofIn = true; inflater.setInput(ByteArray(1), 0, 1) } else inflater.setInput(input, 0, r)
            }
        }
    }

    override fun close() { inflater.end(); raw.close() }
}
