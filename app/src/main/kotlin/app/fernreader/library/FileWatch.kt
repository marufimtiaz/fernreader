package app.fernreader.library

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

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
 * also checked on demand ([checkNow], [unchanged]), on a background thread: gone, another file (inode), or rewritten
 * (size or mtime). C-108 (b): when the app returns to the foreground and before a write that depends on the file, not by
 * a timer (a 1 s poll woke a thread for as long as a book was open).
 */
class FileWatch(private val path: String, private val onGone: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val mask = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB
    /** Set once by whichever notices first (inotify thread, a check, or stop()). */
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
    private val stamp = stampOf(path) // one stat on the caller's thread: the inode for ATTRIB and the checks' baseline
    private fun check() { if (!fired.get() && stampOf(path) != stamp) gone() }

    /** Checks the file now, in the background; a change reaches [onGone] as an inotify event would. */
    fun checkNow() { if (!fired.get()) CHECKER.execute(::check) }

    /** Checks the file now and says whether it is still the one opened (false once a change was seen, or after stop()). */
    suspend fun unchanged(): Boolean = withContext(CHECKS) { check(); !fired.get() }

    /** A callback posted just before stop() must not run after it: it would close the book opened next. */
    private fun gone() {
        if (!fired.compareAndSet(false, true)) return
        main.post { if (!stopped) onGone() }
    }
    fun start() = apply { observer.startWatching() }
    fun stop() { stopped = true; fired.set(true); observer.stopWatching() }

    private companion object {
        /** One daemon thread for all watches (one book is open at a time); it sleeps until a check is asked for. */
        val CHECKER: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "file-watch").apply { isDaemon = true } }
        val CHECKS = CHECKER.asCoroutineDispatcher()
    }
}
