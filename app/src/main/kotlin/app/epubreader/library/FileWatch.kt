package app.epubreader.library

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R-008: tells the reader when the open book's file is deleted, moved away or rewritten. The open descriptor keeps
 * reads valid until the reader closes the book, so nothing crashes in between. [onGone] runs on the main thread.
 *
 * While the reader holds the file open, deleting it only unlinks it (no DELETE_SELF until the last descriptor
 * closes): that arrives as ATTRIB. So ATTRIB counts when the path is gone or now names another file (replaced by a
 * rename, as downloaders and sync apps do).
 *
 * Shared storage (/storage/emulated, FUSE) delivers no inotify events for changes made through FUSE, which is how every
 * app (a file manager, a downloader) changes it: there a delete or replace went unnoticed (M9, R-008). So the path is
 * also checked once a second on a background thread: gone, another file (inode), or rewritten (size or mtime).
 */
class FileWatch(private val path: String, private val onGone: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val mask = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB
    /** Set once by whichever notices first (inotify thread, poll thread, or stop()). */
    private val fired = AtomicBoolean(false)
    @Volatile private var stopped = false
    private val observer: FileObserver =
        if (Build.VERSION.SDK_INT >= 29) object : FileObserver(File(path), mask) { override fun onEvent(event: Int, p: String?) = on(event) }
        else @Suppress("DEPRECATION") object : FileObserver(path, mask) { override fun onEvent(event: Int, p: String?) = on(event) }

    private fun on(event: Int) {
        if (event and FileObserver.ATTRIB != 0 && event and (mask and FileObserver.ATTRIB.inv()) == 0) {
            val now = inodeOf(path)
            if (now != null && now == stamp?.inode) return // permissions or times changed; still the same file
        }
        gone()
    }

    private fun inodeOf(p: String): Long? = try { Os.stat(p).st_ino } catch (e: ErrnoException) { null }

    private data class Stamp(val inode: Long, val size: Long, val mtimeSec: Long) // st_mtim (ns) needs API 27
    private fun stampOf(p: String): Stamp? = try {
        Os.stat(p).let { Stamp(it.st_ino, it.st_size, it.st_mtime) }
    } catch (e: ErrnoException) { null }
    private val stamp = stampOf(path) // one stat on the caller's thread: the inode for ATTRIB and the poll's baseline
    @Volatile private var poll: ScheduledFuture<*>? = null

    private fun check() { if (!fired.get() && stampOf(path) != stamp) gone() }

    /** A callback posted just before stop() must not run after it: it would close the book opened next. */
    private fun gone() {
        if (!fired.compareAndSet(false, true)) return
        poll?.cancel(false)
        main.post { if (!stopped) onGone() }
    }
    fun start() = apply {
        observer.startWatching()
        poll = POLLER.scheduleWithFixedDelay(::check, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
    }
    fun stop() { stopped = true; fired.set(true); observer.stopWatching(); poll?.cancel(false) }

    private companion object {
        const val POLL_MS = 1_000L
        /** One daemon thread for all watches (one book is open at a time). */
        val POLLER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "file-watch").apply { isDaemon = true } }
    }
}
