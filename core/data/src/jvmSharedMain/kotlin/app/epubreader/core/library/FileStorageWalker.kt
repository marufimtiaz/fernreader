package app.epubreader.core.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * F-010/R-010: walks shared storage with java.io.File (never Files.walk, which aborts on the first unreadable
 * directory such as Android/data). Every file named *.epub in any case, in every folder including dot-folders,
 * `.nomedia` folders and Android/media. Unreadable or vanished directories are skipped; symlink loops are cut by
 * remembering canonical directory paths. [firstDirs] (relative to [root]) are walked first because books usually
 * live there, so the first book shows up early (M0 S3).
 */
class FileStorageWalker(
    override val root: String,
    private val firstDirs: List<String> = listOf("Books", "Download", "Documents"),
) : StorageWalker {
    override suspend fun walk(scope: ScanScope, emit: suspend (FoundFile) -> Unit) {
        val visited = HashSet<String>()
        when (scope) {
            is ScanScope.Folder -> walkTree(File(scope.path), visited, emptySet(), emit)
            ScanScope.Everywhere -> {
                val first = firstDirs.map { File(root, it) }
                for (d in first) walkTree(d, visited, emptySet(), emit)
                walkTree(File(root), visited, first.map { it.path }.toSet(), emit)
            }
        }
    }

    private suspend fun walkTree(top: File, visited: MutableSet<String>, skip: Set<String>, emit: suspend (FoundFile) -> Unit) {
        val stack = ArrayDeque<File>().apply { add(top) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
            if (!visited.add(canonical)) continue
            val children = dir.listFiles() ?: continue // unreadable, vanished or not a directory
            for (c in children) {
                if (c.path in skip) continue
                if (c.isDirectory) stack.add(c)
                else if (c.name.endsWith(".epub", ignoreCase = true) && c.isFile) emit(FoundFile(c.path, dir.path, c.length(), c.lastModified()))
            }
        }
    }
}
