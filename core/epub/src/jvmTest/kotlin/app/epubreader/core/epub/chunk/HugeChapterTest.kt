package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The corpus's 10 MB single-chapter book (corpus.md) on the JVM, default policy: bounded time and heap for the
 * first chunking (R-006: processed incrementally, parts on disk) and a fast reopen from the cache (P-016).
 * The device timings (P-014, P-023, P-061) are release checks in M9 (C-015, C-020).
 */
class HugeChapterTest {
    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** Runs [block] while sampling heap use every millisecond; returns (result, ms, peak growth in bytes). */
    private fun <T> measure(block: () -> T): Triple<T, Long, Long> {
        System.gc(); Thread.sleep(100); System.gc()
        val base = usedHeap()
        val peak = AtomicLong(base)
        val running = AtomicBoolean(true)
        val sampler = thread(isDaemon = true) { while (running.get()) { peak.accumulateAndGet(usedHeap(), ::maxOf); Thread.sleep(1) } }
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        peak.accumulateAndGet(usedHeap(), ::maxOf)
        running.set(false)
        sampler.join()
        return Triple(r, ms, peak.get() - base)
    }

    @Test fun huge_chapter_chunks_in_bounded_time_and_memory() {
        val dir = Files.createTempDirectory("huge-chunks").toFile()
        try {
            openFileSource(Corpus.generated("stress-huge-single-chapter.epub").path).use { src ->
                val (z, r) = PackageLoader.open(src)
                val pkg = (r as LoadResult.Ok).pkg
                val docSize = z!!.entry(pkg.spine.single().path)!!.size
                val runs = (1..3).map { i ->
                    val run = File(dir, "run$i")
                    measure { Chunker().chunk(z, pkg, FileChunkStore(run)) }
                }
                val (cb, _, _) = runs.last()
                val (reopened, reopenMs, reopenHeap) = measure { Chunker().chunk(z, pkg, FileChunkStore(File(dir, "run3"))) }
                val d = cb.map.docs.values.single()
                val partSizes = d.parts.map { cb.virtualEntry(it.path)!!.size }
                println("huge chapter: ${docSize / 1024} KB -> ${d.parts.size} parts (max ${partSizes.max() / 1024} KB); " +
                    "chunk ms ${runs.map { it.second }} (first is cold JIT), peak heap growth MB ${runs.map { it.third shr 20 }}; " +
                    "reopen from cache $reopenMs ms, ${reopenHeap shr 20} MB")

                assertTrue(d.parts.size in 60..100, "${d.parts.size} parts")
                assertTrue(partSizes.all { it < 300_000 }, "part sizes $partSizes")
                assertTrue(reopened.map == cb.map)
                assertTrue(runs.drop(1).all { it.second < 10_000 }, "chunking took ${runs.map { it.second }} ms")
                assertTrue(reopenMs < 2_000, "reopen took $reopenMs ms")
                // Heap growth above counts garbage not yet collected; the live bound is checked in a small child JVM.
                assertTrue(runs.all { it.third < 40 * docSize }, "peak heap growth ${runs.map { it.third shr 20 }} MB")
            }
        } finally { dir.deleteRecursively() }
    }

    /** R-006: the whole first chunking of the 10 MB chapter fits in a 64 MB heap (about 6x the document). */
    @Test fun huge_chapter_chunks_within_a_64_mb_heap() {
        val dir = Files.createTempDirectory("huge-heap").toFile()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val p = ProcessBuilder(java, "-Xmx64m", "-cp", System.getProperty("java.class.path"), HugeChunkMain::class.java.name,
                Corpus.generated("stress-huge-single-chapter.epub").path, File(dir, "store").path)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, out.take(2000))
            assertTrue("parts=80" in out, out)
        } finally { dir.deleteRecursively() }
    }
}
