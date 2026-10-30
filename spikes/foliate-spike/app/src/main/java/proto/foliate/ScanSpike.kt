package proto.foliate

import android.os.Environment
import android.os.SystemClock
import proto.common.Probe
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** SPIKE (M0 S3): storage walk strategies and per-file identity/metadata cost. */
object ScanSpike {
    private fun isEpubName(n: String) = n.endsWith(".epub", ignoreCase = true)

    fun walkRecursive(root: File, out: MutableList<File>) {
        val kids = root.listFiles() ?: return
        for (k in kids) if (k.isDirectory) walkRecursive(k, out) else if (isEpubName(k.name) && k.isFile) out += k
    }

    fun walkParallel(root: File, threads: Int = 4): List<File> {
        val out = ConcurrentLinkedQueue<File>()
        val pool = Executors.newFixedThreadPool(threads)
        root.listFiles()?.forEach { top ->
            if (top.isDirectory) pool.execute { val l = ArrayList<File>(); walkRecursive(top, l); out += l }
            else if (isEpubName(top.name) && top.isFile) out += top
        }
        pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
        return out.toList()
    }

    // Ruling: Files.walk aborts on the first unreadable directory (Android/data); walkFileTree can skip it.
    fun walkNio(root: File): List<File> {
        val out = ArrayList<File>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(p: Path, a: BasicFileAttributes): FileVisitResult {
                if (a.isRegularFile && isEpubName(p.fileName.toString())) out += p.toFile()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(p: Path, e: IOException) = FileVisitResult.CONTINUE
        })
        return out
    }

    /** SHA-256 of the zip central directory (read from the end of the file), or null if not a zip. */
    fun cdDigest(f: File): String? = RandomAccessFile(f, "r").use { raf ->
        val len = raf.length(); if (len < 22) return null
        val tailLen = minOf(len, 65_557L).toInt()
        val tail = ByteArray(tailLen); raf.seek(len - tailLen); raf.readFully(tail)
        var i = tailLen - 22
        while (i >= 0 && !(tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte())) i--
        if (i < 0) return null
        fun u32(o: Int) = (tail[o].toLong() and 0xff) or ((tail[o + 1].toLong() and 0xff) shl 8) or
            ((tail[o + 2].toLong() and 0xff) shl 16) or ((tail[o + 3].toLong() and 0xff) shl 24)
        val cdSize = u32(i + 12); val cdOff = u32(i + 16)
        if (cdOff + cdSize > len) return null
        val cd = ByteArray(cdSize.toInt()); raf.seek(cdOff); raf.readFully(cd)
        MessageDigest.getInstance("SHA-256").digest(cd).joinToString("") { "%02x".format(it) }
    }

    private val FULL_PATH = Regex("full-path=\"([^\"]+)\"")
    private val TITLE = Regex("<dc:title[^>]*>([^<]*)<", RegexOption.IGNORE_CASE)

    /** Minimal metadata parse: container.xml → OPF → title (stands in for M2's PackageLoader). */
    fun metadata(f: File): String? = runCatching {
        ZipFile(f).use { z ->
            val c = z.getEntry("META-INF/container.xml") ?: return null
            val opf = FULL_PATH.find(z.getInputStream(c).readBytes().decodeToString())?.groupValues?.get(1) ?: return null
            val e = z.getEntry(opf) ?: return null
            TITLE.find(z.getInputStream(e).readBytes().decodeToString())?.groupValues?.get(1) ?: ""
        }
    }.getOrNull()

    /** mode: full (digest + metadata) | stat (length/lastModified only, models the unchanged rescan) */
    fun run(strategy: String, mode: String = "full", workers: Int = 1) {
        val root = Environment.getExternalStorageDirectory()
        val t0 = SystemClock.uptimeMillis()
        val files = when (strategy) {
            "recursive" -> ArrayList<File>().also { walkRecursive(root, it) }
            "parallel" -> walkParallel(root)
            else -> walkNio(root)
        }
        val tWalk = SystemClock.uptimeMillis()
        var ok = java.util.concurrent.atomic.AtomicInteger(); var meta = java.util.concurrent.atomic.AtomicInteger()
        val first = java.util.concurrent.atomic.AtomicLong(-1)
        var maxFileMs = 0L; var maxFile = ""
        val work = { f: File ->
            val s = SystemClock.uptimeMillis()
            f.length(); f.lastModified()
            if (mode != "stat") {
                if (cdDigest(f) != null) {
                    if (metadata(f) != null) {
                        meta.incrementAndGet()
                        first.compareAndSet(-1, SystemClock.uptimeMillis() - t0)
                    }
                    ok.incrementAndGet()
                }
            }
            val d = SystemClock.uptimeMillis() - s
            synchronized(this) { if (d > maxFileMs) { maxFileMs = d; maxFile = f.name } }
        }
        if (workers <= 1) files.forEach(work) else {
            val pool = Executors.newFixedThreadPool(workers)
            files.forEach { f -> pool.execute { work(f) } }
            pool.shutdown(); pool.awaitTermination(120, TimeUnit.SECONDS)
        }
        Probe.ev("scan_spike", "strategy=$strategy mode=$mode workers=$workers files=${files.size} ok=$ok meta=$meta " +
            "walk_ms=${tWalk - t0} total_ms=${SystemClock.uptimeMillis() - t0} first_ms=$first max_file_ms=$maxFileMs max_file=$maxFile")
    }

    /** Digest time of one file (the 127 MB book), repeated. */
    fun digestOne(path: String) {
        val f = File(path)
        val s = SystemClock.uptimeMillis(); val d = cdDigest(f)
        val m = SystemClock.uptimeMillis(); val t = metadata(f)
        Probe.ev("scan_digest", "file=${f.name} size=${f.length()} digest_ms=${m - s} meta_ms=${SystemClock.uptimeMillis() - m} ok=${d != null && t != null}")
    }
}
