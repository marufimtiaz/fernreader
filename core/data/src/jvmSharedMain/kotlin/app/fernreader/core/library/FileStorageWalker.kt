package app.fernreader.core.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * F-010/R-010: walks shared storage with java.io.File (never Files.walk, which aborts on the first unreadable
 * directory such as Android/data). Every file named *.epub in any case, in every folder including dot-folders,
 * `.nomedia` folders and Android/media. Unreadable or vanished directories are skipped; symlink loops are cut by
 * remembering canonical directory paths. [firstDirs] (relative to [root]) are walked first because books usually
 * live there, so the first book shows up early (M0 S3).
 *
 * C-075: listing a directory costs a stat per entry (FUSE), so a storage full of photos and app files made every
 * scan slow. The walker remembers each directory's listing (its subfolders and book files) with its mtime: a
 * directory whose mtime is unchanged has the same entries (adding, removing or renaming an entry changes it), so it
 * costs one stat instead of a listing; its book files are still stat'ed for size and mtime (a rewrite in place
 * does not touch the directory). A listing taken within [RACY_MS] of the directory's mtime is not trusted the next
 * time (a change in the same clock tick would not show). The memory lasts for the process; the first walk lists
 * everything.
 */
class FileStorageWalker(
    override val root: String,
    private val firstDirs: List<String> = listOf("Books", "Download", "Documents"),
    private val list: (File) -> Array<File>? = File::listFiles,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : StorageWalker {
    /** A directory as last listed: [mtimeMs] then, and when it was listed. */
    private class Listing(val canonical: String, val mtimeMs: Long, val listedAt: Long, val dirs: List<File>, val books: List<File>)
    /** By path, updated by each walk. Walks never overlap (the scanner holds a lock). */
    private var listings = HashMap<String, Listing>()

    override suspend fun walk(scope: ScanScope, emit: suspend (FoundFile) -> Unit) {
        val visited = HashSet<String>()
        val seen = HashMap<String, Listing>()
        var completed = false
        try { walkScope(scope, visited, seen, emit); completed = true } finally {
            // A completed walk forgets the directories under its top that it did not see (removed); a walk cut short
            // only adds what it listed.
            val top = when (scope) { ScanScope.Everywhere -> root; is ScanScope.Folder -> scope.path }.trimEnd('/')
            listings = HashMap(listings).apply {
                if (completed) keys.removeAll { it == top || it.startsWith("$top/") }
                putAll(seen)
            }
        }
    }

    private suspend fun walkScope(scope: ScanScope, visited: MutableSet<String>, seen: MutableMap<String, Listing>, emit: suspend (FoundFile) -> Unit) {
        when (scope) {
            is ScanScope.Folder -> walkTree(File(scope.path), visited, seen, emptySet(), emit, required = true)
            ScanScope.Everywhere -> {
                val first = firstDirs.map { File(root, it) }
                for (d in first) walkTree(d, visited, seen, emptySet(), emit)
                walkTree(File(root), visited, seen, first.map { it.path }.toSet(), emit, required = true)
            }
        }
    }

    /**
     * [required]: the scan root or the chosen folder. If it cannot be listed (storage unmounted, folder removed), the
     * walk fails instead of "finding nothing", so the scanner prunes nothing (final review M1).
     */
    private suspend fun walkTree(
        top: File, visited: MutableSet<String>, seen: MutableMap<String, Listing>, skip: Set<String>,
        emit: suspend (FoundFile) -> Unit, required: Boolean = false,
    ) {
        if (required && list(top) == null) throw java.io.IOException("cannot list ${top.path}")
        val stack = ArrayDeque<File>().apply { add(top) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val l = listing(dir) ?: continue // unreadable, vanished or not a directory
            if (!visited.add(l.canonical)) continue
            seen[dir.path] = l
            for (c in l.dirs) if (c.path !in skip) stack.add(c)
            for (b in l.books) {
                val mtime = b.lastModified()
                if (mtime != 0L) emit(FoundFile(b.path, dir.path, b.length(), mtime)) // 0: gone since (the next walk lists the dir)
            }
        }
    }

    /** [dir]'s entries: remembered while its mtime is unchanged, else listed (and remembered). */
    private fun listing(dir: File): Listing? {
        val mtime = dir.lastModified()
        if (mtime == 0L && !dir.exists()) return null // e.g. a first folder that is not there
        val known = listings[dir.path]
        if (known != null && mtime != 0L && mtime == known.mtimeMs && known.listedAt - mtime >= RACY_MS) return known
        val listedAt = nowMs()
        val children = list(dir) ?: return null
        val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return null
        val dirs = ArrayList<File>(); val books = ArrayList<File>()
        for (c in children) {
            if (c.isDirectory) dirs.add(c)
            else if (c.name.endsWith(".epub", ignoreCase = true) && c.isFile) books.add(c)
        }
        return Listing(canonical, mtime, listedAt, dirs, books)
    }

    private companion object { const val RACY_MS = 2_000L }
}
