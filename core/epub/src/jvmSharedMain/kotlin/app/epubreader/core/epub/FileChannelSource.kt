package app.epubreader.core.epub

import app.epubreader.core.epub.zip.RandomAccessSource
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional reads on a FileChannel are thread-safe; used for files and (M4) ParcelFileDescriptors. */
class FileChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val size: Long = channel.size()
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int =
        if (position >= size) -1 else channel.read(ByteBuffer.wrap(dst, off, len), position)
    override fun close() = channel.close()
}
