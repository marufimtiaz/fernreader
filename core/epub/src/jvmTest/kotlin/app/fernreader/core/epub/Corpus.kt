package app.fernreader.core.epub

import java.io.File

/** Generated corpus (always present: Gradle generateTestCorpus) and the optional downloaded corpus. */
object Corpus {
    val dir = File(System.getProperty("corpus.dir") ?: error("corpus.dir not set; run through Gradle"))
    fun generated(name: String) = File(dir, "generated/$name").also { require(it.exists()) { "missing $it" } }
    fun folder(name: String) = File(dir, "folders/$name").also { require(it.exists()) { "missing $it" } }
    val full: File? = System.getProperty("fullcorpus.dir")?.let(::File)?.takeIf { File(it, "gutenberg").isDirectory }
}
