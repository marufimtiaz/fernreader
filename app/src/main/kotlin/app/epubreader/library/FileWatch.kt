package app.epubreader.library

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import java.io.File

/**
 * R-008: tells the reader when the open book's file is deleted, moved away or rewritten. The open descriptor keeps
 * reads valid until the reader closes the book, so nothing crashes in between. [onGone] runs on the main thread.
 *
 * While the reader holds the file open, deleting it only unlinks it (no DELETE_SELF until the last descriptor
 * closes): that arrives as ATTRIB. So ATTRIB counts when the path is gone or now names another file (replaced by a
 * rename, as downloaders and sync apps do).
 */
class FileWatch(private val path: String, private val onGone: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val mask = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB
    private val inode = inodeOf(path)
    @Volatile private var fired = false
    private val observer: FileObserver =
        if (Build.VERSION.SDK_INT >= 29) object : FileObserver(File(path), mask) { override fun onEvent(event: Int, p: String?) = on(event) }
        else @Suppress("DEPRECATION") object : FileObserver(path, mask) { override fun onEvent(event: Int, p: String?) = on(event) }

    private fun on(event: Int) {
        if (event and FileObserver.ATTRIB != 0 && event and (mask and FileObserver.ATTRIB.inv()) == 0) {
            val now = inodeOf(path)
            if (now != null && now == inode) return // permissions or times changed; still the same file
        }
        gone()
    }

    private fun inodeOf(p: String): Long? = try { Os.stat(p).st_ino } catch (e: ErrnoException) { null }

    private fun gone() { if (!fired) { fired = true; main.post(onGone) } }
    fun start() = apply { observer.startWatching() }
    fun stop() { fired = true; observer.stopWatching() }
}
