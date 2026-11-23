package app.fernreader.core.epub.chunk

import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.pkg.LoadResult
import app.fernreader.core.epub.pkg.PackageLoader
import java.io.File
import kotlin.system.exitProcess

/** Child-JVM entry for [HugeChapterTest]: chunks args[0] into the store dir args[1] and prints the part count. */
object HugeChunkMain {
    @JvmStatic fun main(args: Array<String>) {
        openFileSource(args[0]).use { src ->
            val (z, r) = PackageLoader.open(src)
            val cb = Chunker().chunk(z!!, (r as LoadResult.Ok).pkg, FileChunkStore(File(args[1])))
            println("parts=${cb.map.docs.values.sumOf { it.parts.size }}")
        }
        exitProcess(0)
    }
}
